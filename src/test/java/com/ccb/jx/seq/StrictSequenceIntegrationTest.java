
package com.ccb.jx.seq;

import com.ccb.jx.seq.core.SequenceService;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;
import com.ccb.jx.seq.core.StrictSequence;
import com.ccb.jx.seq.session.SessionHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link StrictSequence} 集成测试。
 * <p>
 * 验证 STRICT 模式的完整执行路径：从 {@link SequenceService#nextVal(String)}
 * 路由到 {@link StrictSequence#nextVal(String)}，
 * 经过 {@code SELECT ... FOR UPDATE} 锁定、CAS 更新 {@code max_id} 的完整数据库交互。
 * </p>
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>正常递增（含自定义 {@code INCREMENT BY}）</li>
 *   <li>严格连续语义验证</li>
 *   <li>{@code CYCLE} 回绕行为</li>
 *   <li>序列耗尽异常</li>
 *   <li>{@code CURRVAL} 会话语义</li>
 * </ul>
 *
 * @author XZJ
 */
@SpringJUnitConfig(SequenceTestBase.TestConfiguration.class)
@Sql(scripts = "classpath:sql/init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("StrictSequence 严格连续模式集成测试")
class StrictSequenceIntegrationTest {

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private SequenceRepository sequenceRepository;

    @AfterEach
    void cleanSession() {
        SessionHolder.clear();
    }

    @Nested
    @DisplayName("nextVal 基本功能")
    class NextValBasicTests {

        @Test
        @DisplayName("nextVal - 首次调用返回 startWith 值")
        void nextVal_shouldReturnStartWith_onFirstCall() {
            // TEST_STRICT 的 start_with=1, max_id=1
            // 下一个值 = 1 + 1 = 2
            long value = sequenceService.nextVal("TEST_STRICT");
            assertThat(value).isEqualTo(2L);
        }

        @Test
        @DisplayName("nextVal - 连续多次调用，值严格递增")
        void nextVal_shouldBeStrictlyIncreasing() {
            long v1 = sequenceService.nextVal("TEST_STRICT");
            long v2 = sequenceService.nextVal("TEST_STRICT");
            long v3 = sequenceService.nextVal("TEST_STRICT");

            assertThat(v1).isEqualTo(2L);
            assertThat(v2).isEqualTo(3L);
            assertThat(v3).isEqualTo(4L);
        }

        @Test
        @DisplayName("nextVal - 支持自定义 INCREMENT BY = 2")
        void nextVal_shouldRespectIncrementBy() {
            // TEST_STRICT_STEP2 的 increment_by=2, max_id=1
            // 返回值: 3, 5, 7, ...
            long v1 = sequenceService.nextVal("TEST_STRICT_STEP2");
            long v2 = sequenceService.nextVal("TEST_STRICT_STEP2");

            assertThat(v1).isEqualTo(3L);
            assertThat(v2).isEqualTo(5L);
        }

        @Test
        @DisplayName("nextVal - 不存在的序列自动创建并分配值")
        void nextVal_shouldAutoCreate_whenSeqNotExists() {
            // getConfig 找不到配置时会自动创建默认 STRICT 行
            long value = sequenceService.nextVal("NON_EXISTENT");
            assertThat(value).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("nextVal CYCLE 行为")
    class NextValCycleTests {

        @Test
        @DisplayName("nextVal - CYCLE 序列到达 max_value 后回绕到 min_value")
        void nextVal_shouldCycle_whenReachingMaxValue() {
            // TEST_CYCLE: max_id=1, increment_by=1, min_value=1, max_value=10, cycle=1
            // 第1次: 2, 第2次: 3, ..., 第9次: 10, 第10次: 1（回绕）
            long value = 0;
            for (int i = 0; i < 9; i++) {
                value = sequenceService.nextVal("TEST_CYCLE");
            }
            // 第9次应该返回 10
            assertThat(value).isEqualTo(10L);

            // 第10次应该回绕到 1
            long cycledValue = sequenceService.nextVal("TEST_CYCLE");
            assertThat(cycledValue).isEqualTo(1L);
        }

        @Test
        @DisplayName("nextVal - CYCLE 序列回绕后继续递增")
        void nextVal_shouldContinueAfterCycle() {
            // 消费到回绕点
            for (int i = 0; i < 10; i++) {
                sequenceService.nextVal("TEST_CYCLE");
            }
            // 回绕后的下一个值
            long afterCycle = sequenceService.nextVal("TEST_CYCLE");

            // 回绕后 max_id=1（min_value），下一个值 = 1 + 1 = 2
            assertThat(afterCycle).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("nextVal 序列耗尽")
    class NextValExhaustedTests {

        @Test
        @DisplayName("nextVal - 非 CYCLE 序列达到 max_value 时抛出 SEQ_EXHAUSTED")
        void nextVal_shouldThrow_whenExceedsMaxValue() {
            // 将 max_id 设置为接近 max_value 的值
            sequenceRepository.updateMaxId("TEST_STRICT", 10000L, 1L);

            // 下一次调用应抛出异常（10000 + 1 > 10000）
            assertThatThrownBy(() -> sequenceService.nextVal("TEST_STRICT"))
                    .isInstanceOf(SequenceException.class)
                    .satisfies(e -> {
                        SequenceException se = (SequenceException) e;
                        assertThat(se.getErrorCode()).isEqualTo(SequenceErrorCode.SEQ_EXHAUSTED);
                    });
        }
    }

    @Nested
    @DisplayName("CURRVAL 会话语义")
    class CurrValTests {

        @Test
        @DisplayName("currVal - nextVal 后返回正确的当前值")
        void currVal_shouldReturnLastNextVal() {
            sequenceService.nextVal("TEST_STRICT");
            long currVal = sequenceService.currVal("TEST_STRICT");

            assertThat(currVal).isEqualTo(2L);
        }

        @Test
        @DisplayName("currVal - 多次 nextVal 后返回最后一次的值")
        void currVal_shouldReturnLatestNextVal() {
            sequenceService.nextVal("TEST_STRICT"); // 2
            sequenceService.nextVal("TEST_STRICT"); // 3
            sequenceService.nextVal("TEST_STRICT"); // 4
            long currVal = sequenceService.currVal("TEST_STRICT");

            assertThat(currVal).isEqualTo(4L);
        }

        @Test
        @DisplayName("currVal - 未调用 nextVal 时抛出 CURRVAL_NOT_INITIALIZED")
        void currVal_shouldThrow_whenNotInitialized() {
            assertThatThrownBy(() -> sequenceService.currVal("TEST_STRICT"))
                    .isInstanceOf(SequenceException.class)
                    .satisfies(e -> {
                        SequenceException se = (SequenceException) e;
                        assertThat(se.getErrorCode())
                                .isEqualTo(SequenceErrorCode.CURRVAL_NOT_INITIALIZED);
                    });
        }

        @Test
        @DisplayName("currVal - 不同序列独立记录")
        void currVal_shouldBeIndependentPerSequence() {
            sequenceService.nextVal("TEST_STRICT");      // 2
            sequenceService.nextVal("TEST_STRICT_STEP2"); // 3

            assertThat(sequenceService.currVal("TEST_STRICT")).isEqualTo(2L);
            assertThat(sequenceService.currVal("TEST_STRICT_STEP2")).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("数据库状态验证")
    class DatabaseStateTests {

        @Test
        @DisplayName("nextVal 后 max_id 在数据库中被更新")
        void nextVal_shouldUpdateMaxIdInDatabase() {
            sequenceService.nextVal("TEST_STRICT");

            SequenceConfig config = sequenceRepository.selectConfig("TEST_STRICT");
            assertThat(config.getMaxId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("连续 nextVal 后 max_id 持续更新")
        void nextVal_shouldUpdateMaxIdConsistently() {
            for (int i = 0; i < 5; i++) {
                sequenceService.nextVal("TEST_STRICT");
            }

            SequenceConfig config = sequenceRepository.selectConfig("TEST_STRICT");
            // 初始 max_id=1，5次调用 -> max_id=6
            assertThat(config.getMaxId()).isEqualTo(6L);
        }

        @Test
        @DisplayName("不同序列的 max_id 独立更新")
        void nextVal_shouldUpdateIndependentMaxIds() {
            sequenceService.nextVal("TEST_STRICT");
            sequenceService.nextVal("TEST_STRICT_STEP2");

            SequenceConfig strictConfig = sequenceRepository.selectConfig("TEST_STRICT");
            SequenceConfig step2Config = sequenceRepository.selectConfig("TEST_STRICT_STEP2");

            assertThat(strictConfig.getMaxId()).isEqualTo(2L);
            assertThat(step2Config.getMaxId()).isEqualTo(3L);
        }
    }
}
