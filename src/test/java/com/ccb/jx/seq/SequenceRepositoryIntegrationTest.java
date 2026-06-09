
package com.ccb.jx.seq;

import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.RecordStatus;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.model.SequenceRecord;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SequenceRepository} MyBatis Mapper 集成测试。
 * <p>
 * 验证 MyBatis XML 映射文件与数据库之间的交互正确性，包括：
 * <ul>
 *   <li>sequence_config 表的 CRUD 操作</li>
 *   <li>sequence_record 表的插入和状态更新</li>
 * </ul>
 * </p>
 *
 * @author XZJ
 */
@SpringJUnitConfig(SequenceTestBase.TestConfiguration.class)
@Sql(scripts = "classpath:sql/init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("SequenceRepository MyBatis Mapper 集成测试")
class SequenceRepositoryIntegrationTest {

    @Autowired
    private SequenceRepository sequenceRepository;

    // ==================== sequence_config 操作 ====================

    @Nested
    @DisplayName("sequence_config 查询操作")
    class ConfigQueryTests {

        @Test
        @DisplayName("selectConfig - 按名称查询存在的序列配置")
        void selectConfig_shouldReturnConfig() {
            SequenceConfig config = sequenceRepository.selectConfig("TEST_STRICT");

            assertThat(config).isNotNull();
            assertThat(config.getSeqName()).isEqualTo("TEST_STRICT");
            assertThat(config.getMode()).isEqualTo(Mode.STRICT);
            assertThat(config.getMaxId()).isEqualTo(1L);
            assertThat(config.getIncrementBy()).isEqualTo(1);
            assertThat(config.getMinValue()).isEqualTo(1L);
            assertThat(config.getMaxValue()).isEqualTo(10000L);
            assertThat(config.getCycle()).isEqualTo(0);
            assertThat(config.getStartWith()).isEqualTo(1L);
            assertThat(config.getDescription()).isEqualTo("Test strict sequence");
        }

        @Test
        @DisplayName("selectConfig - 查询不存在的序列返回 null")
        void selectConfig_shouldReturnNull_whenNotExists() {
            SequenceConfig config = sequenceRepository.selectConfig("NON_EXISTENT_SEQUENCE");
            assertThat(config).isNull();
        }

        @Test
        @DisplayName("selectConfigForUpdate - 带行锁查询（H2 MySQL 模式支持 FOR UPDATE）")
        void selectConfigForUpdate_shouldReturnConfig() {
            SequenceConfig config = sequenceRepository.selectConfigForUpdate("TEST_STRICT");

            assertThat(config).isNotNull();
            assertThat(config.getSeqName()).isEqualTo("TEST_STRICT");
            assertThat(config.getMode()).isEqualTo(Mode.STRICT);
        }

        @Test
        @DisplayName("selectConfigForUpdate - 查询不存在的序列返回 null")
        void selectConfigForUpdate_shouldReturnNull_whenNotExists() {
            SequenceConfig config = sequenceRepository.selectConfigForUpdate("NON_EXISTENT");
            assertThat(config).isNull();
        }

        @Test
        @DisplayName("selectAllConfig - 查询所有序列配置")
        void selectAllConfig_shouldReturnAllConfigs() {
            List<SequenceConfig> configs = sequenceRepository.selectAllConfig();

            assertThat(configs).isNotEmpty();
            assertThat(configs).extracting(SequenceConfig::getSeqName)
                    .contains("TEST_STRICT", "TEST_CACHED", "TEST_CYCLE",
                            "TEST_STRICT_STEP2", "TEST_CACHED_SMALL");
        }
    }

    @Nested
    @DisplayName("sequence_config 更新操作")
    class ConfigUpdateTests {

        @Test
        @DisplayName("updateMaxId - CAS 乐观锁更新成功")
        void updateMaxId_shouldUpdate_whenCasMatch() {
            // max_id 初始值为 1，CAS 条件匹配
            int updated = sequenceRepository.updateMaxId("TEST_STRICT", 101L, 1L);

            assertThat(updated).isEqualTo(1);

            // 验证更新结果
            SequenceConfig config = sequenceRepository.selectConfig("TEST_STRICT");
            assertThat(config.getMaxId()).isEqualTo(101L);
        }

        @Test
        @DisplayName("updateMaxId - CAS 乐观锁冲突返回 0")
        void updateMaxId_shouldReturnZero_whenCasConflict() {
            // max_id 当前为 1，但 CAS 条件指定 oldMaxId=999，预期不匹配
            int updated = sequenceRepository.updateMaxId("TEST_STRICT", 101L, 999L);

            assertThat(updated).isEqualTo(0);

            // 验证 max_id 未被修改
            SequenceConfig config = sequenceRepository.selectConfig("TEST_STRICT");
            assertThat(config.getMaxId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("updateMaxId - 序列不存在时返回 0")
        void updateMaxId_shouldReturnZero_whenSeqNotExists() {
            int updated = sequenceRepository.updateMaxId("NON_EXISTENT", 101L, 1L);
            assertThat(updated).isEqualTo(0);
        }

        @Test
        @DisplayName("updateMaxId - CAS 方式推进成功")
        void updateMaxId_shouldAdvanceByCas() {
            // max_id 初始值为 1，CAS 更新为 1001
            int updated = sequenceRepository.updateMaxId("TEST_STRICT", 1001L, 1L);

            assertThat(updated).isEqualTo(1);

            // 验证 max_id = 1001
            SequenceConfig config = sequenceRepository.selectConfig("TEST_STRICT");
            assertThat(config.getMaxId()).isEqualTo(1001L);
        }

        @Test
        @DisplayName("updateMaxId - CAS oldMaxId 不匹配时返回 0")
        void updateMaxId_shouldReturnZero_whenCasMismatch() {
            // oldMaxId=999 不匹配实际值 1，CAS 失败
            int updated = sequenceRepository.updateMaxId("TEST_STRICT", 2001L, 999L);
            assertThat(updated).isEqualTo(0);
        }
    }

    // ==================== sequence_record 操作 ====================

    @Nested
    @DisplayName("sequence_record 操作")
    class RecordTests {

        @Test
        @DisplayName("insertRecord - 插入号段记录并返回自增主键")
        void insertRecord_shouldInsertAndSetId() {
            SequenceRecord record = new SequenceRecord();
            record.setSeqName("TEST_STRICT");
            record.setStartValue(1L);
            record.setEndValue(1000L);
            record.setInstanceId("test-instance");
            record.setStatus(RecordStatus.ALLOCATED);
            record.setAllocTime(new Date());
            record.setExpireTime(new Date(System.currentTimeMillis() + 120_000));

            int inserted = sequenceRepository.insertRecord(record);

            assertThat(inserted).isEqualTo(1);
            assertThat(record.getId()).isNotNull().isPositive();
        }

        @Test
        @DisplayName("updateRecordStatus - 更新号段记录状态")
        void updateRecordStatus_shouldUpdateStatus() {
            // 先插入记录
            SequenceRecord record = new SequenceRecord();
            record.setSeqName("TEST_STRICT");
            record.setStartValue(1L);
            record.setEndValue(1000L);
            record.setInstanceId("test-instance");
            record.setStatus(RecordStatus.ALLOCATED);
            record.setAllocTime(new Date());
            sequenceRepository.insertRecord(record);
            Long recordId = record.getId();

            // 更新状态
            int updated = sequenceRepository.updateRecordStatus(recordId, RecordStatus.EXPIRED);
            assertThat(updated).isEqualTo(1);
        }

        @Test
        @DisplayName("updateRecordStatus - 不存在的记录 ID 返回 0")
        void updateRecordStatus_shouldReturnZero_whenIdNotExists() {
            int updated = sequenceRepository.updateRecordStatus(-1L, RecordStatus.EXPIRED);
            assertThat(updated).isEqualTo(0);
        }
    }

}
