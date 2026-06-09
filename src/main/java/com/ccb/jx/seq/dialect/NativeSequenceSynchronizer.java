
package com.ccb.jx.seq.dialect;

import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 原生 Sequence 启动同步器。
 * <p>
 * 在应用启动时，扫描 {@code sequence_config} 表中所有 {@code mode=STRICT} 的序列，
 * 确保对应的数据库原生 Sequence 对象已存在于 DB 中：
 * <ul>
 *   <li>不存在 → 自动 {@code CREATE}</li>
 *   <li>已存在 → 跳过</li>
 * </ul>
 * </p>
 *
 * @author XZJ
 */
public class NativeSequenceSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(NativeSequenceSynchronizer.class);

    private final SequenceRepository repository;
    private final NativeSequenceDialect dialect;

    /**
     * 构造同步器。
     *
     * @param repository 序列数据访问层
     * @param dialect    原生 Sequence 方言实现
     */
    public NativeSequenceSynchronizer(SequenceRepository repository,
                                      NativeSequenceDialect dialect) {
        this.repository = repository;
        this.dialect = dialect;
    }

    /**
     * 启动时全量同步：扫描所有 {@code mode=STRICT} 的序列，
     * 为每个未创建原生 Sequence 的序列执行 {@code CREATE}。
     */
    public void syncOnStartup() {
        log.info("[NATIVE-SYNC] Starting startup sync of native sequences...");

        List<SequenceConfig> allConfigs = repository.selectAllConfig();
        int created = 0;
        int skipped = 0;

        for (SequenceConfig config : allConfigs) {
            if (config.getMode() != Mode.STRICT) {
                continue; // CACHED 模式不创建原生 Sequence
            }

            String seqName = config.getSeqName();
            if (dialect.sequenceExists(seqName)) {
                log.debug("[NATIVE-SYNC] Native sequence already exists, skipping: seqName={}", seqName);
                skipped++;
            } else {
                dialect.createSequence(config);
                log.info("[NATIVE-SYNC] Created native sequence: seqName={}", seqName);
                created++;
            }
        }

        log.info("[NATIVE-SYNC] Startup sync complete: total={}, created={}, skipped={}",
                allConfigs.size(), created, skipped);
    }
}
