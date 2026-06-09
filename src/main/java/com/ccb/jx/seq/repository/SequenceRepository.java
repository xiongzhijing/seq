
package com.ccb.jx.seq.repository;

import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.RecordStatus;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.model.SequenceRecord;
import com.ccb.jx.seq.core.SequenceService;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 序列数据访问层接口。
 * <p>
 * 提供对 sequence_config、sequence_record 两张表的所有
 * 数据库操作。由 MyBatis 通过 {@code SequenceRepository.xml} 映射实现。
 * <p>
 * 事务边界不在本层定义，由上层 Service 通过 {@code @Transactional} 控制。
 *
 * @author XZJ
 */
@Mapper
public interface SequenceRepository {

    // ==================== sequence_config 操作 ====================

    /**
     * 查询序列配置（带行锁）。
     * <p>
     * 使用 {@code SELECT ... FOR UPDATE} 悲观锁锁定行记录，
     * 用于 {@code STRICT} 严格连续模式的 {@code nextVal} 分配。
     *
     * @param seqName 序列名称
     * @return 序列配置，不存在时返回 {@code null}
     */
    SequenceConfig selectConfigForUpdate(@Param("seqName") String seqName);

    /**
     * 查询序列配置（普通查询，无锁）。
     * <p>
     * 用于配置缓存加载和全量查询等无需锁定的场景。
     *
     * @param seqName 序列名称
     * @return 序列配置，不存在时返回 {@code null}
     */
    SequenceConfig selectConfig(@Param("seqName") String seqName);

    /**
     * CAS 方式更新 max_id（严格连续模式使用）。
     * <p>
     * {@code UPDATE sequence_config SET max_id = #{newMaxId}
     *  WHERE seq_name = #{seqName} AND max_id = #{oldMaxId}}
     * <p>
     * 利用 {@code WHERE max_id = #{oldMaxId}} 实现乐观锁，
     * 返回 0 表示并发冲突，需要重试。
     *
     * @param seqName  序列名称
     * @param newMaxId 新的 max_id 值
     * @param oldMaxId 当前的 max_id 值（乐观锁条件）
     * @return 影响行数（0 表示 CAS 失败）
     */
    int updateMaxId(@Param("seqName") String seqName,
                    @Param("newMaxId") long newMaxId,
                    @Param("oldMaxId") long oldMaxId);

    /**
     * 查询所有序列配置。
     * <p>
     * 用于启动时加载全量配置、监控展示等场景。
     *
     * @return 全部序列配置列表
     */
    List<SequenceConfig> selectAllConfig();

    /**
     * 插入序列配置（INSERT IGNORE，并发安全）。
     * <p>
     * 使用 {@code INSERT IGNORE} 保证多实例并发首次写入时仅一条生效。
     * 通常用于 {@link SequenceService#getConfig(String)}
     * 的懒创建场景：当配置不存在时自动插入默认行。
     *
     * @param config 序列配置
     */
    void insertConfigIgnore(SequenceConfig config);

    // ==================== sequence_record 操作 ====================

    /**
     * 插入号段分配记录。
     * <p>
     * 插入成功后，自增主键将通过 {@code useGeneratedKeys} 写回
     * {@link SequenceRecord#id} 字段。
     *
     * @param record 号段分配记录
     * @return 影响行数
     */
    int insertRecord(SequenceRecord record);

    /**
     * 更新号段记录状态。
     * <p>
     * 用于号段消费完毕后的状态变更（ALLOCATED → EXPIRED / RECYCLED）。
     *
     * @param id     记录主键
     * @param status 目标状态
     * @return 影响行数
     */
    int updateRecordStatus(@Param("id") Long id,
                           @Param("status") RecordStatus status);

    /**
     * 查询指定实例的已分配号段记录。
     * <p>
     * 用于审计追踪和崩溃恢复场景，查询指定实例 ID 下状态为 ALLOCATED 的号段记录。
     *
     * @param instanceId 实例标识，可为 {@code null}（查询无实例 ID 的记录）
     * @return 匹配的号段记录列表
     */
    List<SequenceRecord> selectExpiredRecords(@Param("instanceId") String instanceId);

    /**
     * 删除指定时间之前的旧号段记录。
     * <p>
     * 清理 {@code alloc_time} 早于指定时间且状态为 {@code EXPIRED} 或 {@code RECYCLED} 的记录，
     * 防止 {@code sequence_record} 表无限增长。
     *
     * @param beforeTime 截止时间，早于此时间的记录将被删除
     * @return 删除的记录数
     */
    int deleteOldRecords(@Param("beforeTime") java.util.Date beforeTime);
}
