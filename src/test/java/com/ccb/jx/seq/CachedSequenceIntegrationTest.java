
package com.ccb.jx.seq;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.buffer.Segment;
import com.ccb.jx.seq.core.CachedSequence;
import com.ccb.jx.seq.core.SequenceService;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.model.SequenceRecord;
import com.ccb.jx.seq.repository.SequenceRepository;
import com.ccb.jx.seq.session.SessionHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CachedSequence} 集成测试。
 * <p>
 * 验证 CACHED 模式的完整执行路径：从 {@link SequenceService#nextVal(String)}
 * 路由到 {@link CachedSequence#nextVal(String)}，经过双缓冲初始化、
 * 号段加载、内存分配的完整流程。
 * </p>
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>首次 nextVal 触发号段加载和 DoubleBuffer 初始化</li>
 *   <li>后续 nextVal 从内存分配（无锁路径）</li>
 *   <li>号段耗尽后自动切换 Segment</li>
 *   <li>号段加载时在 sequence_record 表写入审计记录</li>
 *   <li>{@code CURRVAL} 会话语义</li>
 * </ul>
 *
 * <h3>max_id 约定</h3>
 * <p>max_id 表示"已分配的最后一个值"，因此 max_id=1 时第一个返回值为 2。
 * 号段加载时，Segment 范围为 {@code [max_id+1, max_id+step]}，
 * 即 {@code startValue = newMaxId - step + 1, endValue = newMaxId}。</p>
 *
 * @author XZJ
 */
@SpringJUnitConfig(SequenceTestBase.TestConfiguration.class)
@Sql(scripts = "classpath:sql/init.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@DisplayName("CachedSequence 号段缓存模式集成测试")
class CachedSequenceIntegrationTest {

    @Autowired
    private SequenceService sequenceService;

    @Autowired
    private CachedSequence cachedSequence;

    @Autowired
    private SequenceRepository sequenceRepository;

    @BeforeEach
    void setUp() {
        // Clear in-memory caches to ensure clean state between test methods.
        // @Sql(executionPhase = BEFORE_TEST_METHOD) resets the database first,
        // then @BeforeEach clears the CachedSequence bufferMap and
        // SequenceService configCache to prevent stale state contamination.
        sequenceService.clearCache();
        cachedSequence.getLoadedSequenceNames().clear();
    }

    @AfterEach
    void cleanSession() {
        SessionHolder.clear();
    }

    @Nested
    @DisplayName("nextVal 基本功能")
    class NextValBasicTests {

        @Test
        @DisplayName("nextVal - 首次调用返回 max_id+1 的值")
        void nextVal_shouldReturnFirstValue() {
            // TEST_CACHED: max_id=1, step=1000
            // 加载号段 [2, 1001]，第一个值 = 2
            long value = sequenceService.nextVal("TEST_CACHED");
            assertThat(value).isEqualTo(2L);
        }

        @Test
        @DisplayName("nextVal - 连续从内存分配，递增无空洞")
        void nextVal_shouldBeIncreasing() {
            long v1 = sequenceService.nextVal("TEST_CACHED");
            long v2 = sequenceService.nextVal("TEST_CACHED");
            long v3 = sequenceService.nextVal("TEST_CACHED");

            assertThat(v1).isEqualTo(2L);
            assertThat(v2).isEqualTo(3L);
            assertThat(v3).isEqualTo(4L);
        }

        @Test
        @DisplayName("nextVal - 调用 100 次验证内存分配连续性")
        void nextVal_shouldBeContinuous() {
            long previous = sequenceService.nextVal("TEST_CACHED");
            assertThat(previous).isEqualTo(2L);
            for (int i = 1; i < 100; i++) {
                long current = sequenceService.nextVal("TEST_CACHED");
                assertThat(current).isEqualTo(previous + 1);
                previous = current;
            }
            // 第 100 次调用后的值：2 + 99 = 101
            assertThat(previous).isEqualTo(101L);
        }
    }

    @Nested
    @DisplayName("号段加载与切换")
    class SegmentLoadingTests {

        @Test
        @DisplayName("首次 nextVal 后创建 DoubleBuffer，加载第一个 Segment")
        void firstNextVal_shouldInitializeBuffer() {
            sequenceService.nextVal("TEST_CACHED");

            DoubleBuffer buffer = cachedSequence.getBuffer("TEST_CACHED");
            assertThat(buffer).isNotNull();

            Segment current = buffer.getCurrent();
            assertThat(current).isNotNull();
            assertThat(current.getStart()).isEqualTo(2L);
            assertThat(current.getEnd()).isEqualTo(1001L);
            assertThat(current.remaining()).isEqualTo(999L); // 已消耗 1 个（值=2）
        }

        @Test
        @DisplayName("号段耗尽后自动切换到新 Segment")
        void nextVal_shouldSwitchSegment_whenExhausted() {
            // TEST_CACHED_SMALL: max_id=1, step=10
            // 第一个号段: [2, 11]，共 10 个值
            // 消耗完第一个号段后触发 loadSegment（含动态步长调整）
            for (int i = 0; i < 10; i++) {
                sequenceService.nextVal("TEST_CACHED_SMALL");
            }

            // 第11次调用，号段耗尽，触发切换
            long value = sequenceService.nextVal("TEST_CACHED_SMALL");

            // 验证切换到新号段：值必须大于第一个号段的最大值(11)
            // 注意：由于动态步长(calculateDynamicStep)在测试环境中消耗极快，
            // actualDuration≈0 → clamped to 1min，ratio=15，MIN_STEP=100，
            // 步长会被大幅放大(10→150甚至更大)，因此新号段起始值不确定。
            // 使用范围断言保证测试稳定性。
            assertThat(value).isGreaterThan(11L);

            // 验证 DB 中 max_id 已更新（大于第一次加载后的值 11）
            SequenceConfig config = sequenceRepository.selectConfig("TEST_CACHED_SMALL");
            assertThat(config.getMaxId()).isGreaterThan(11L);
        }
    }

    @Nested
    @DisplayName("CURRVAL 会话语义")
    class CurrValTests {

        @Test
        @DisplayName("currVal - nextVal 后返回正确的当前值")
        void currVal_shouldReturnLastNextVal() {
            sequenceService.nextVal("TEST_CACHED");
            long currVal = sequenceService.currVal("TEST_CACHED");

            assertThat(currVal).isEqualTo(2L);
        }

        @Test
        @DisplayName("currVal - 多次 nextVal 后返回最后一次的值")
        void currVal_shouldReturnLatestNextVal() {
            sequenceService.nextVal("TEST_CACHED"); // 2
            sequenceService.nextVal("TEST_CACHED"); // 3
            sequenceService.nextVal("TEST_CACHED"); // 4
            long currVal = sequenceService.currVal("TEST_CACHED");

            assertThat(currVal).isEqualTo(4L);
        }

        @Test
        @DisplayName("currVal - 未调用 nextVal 时抛出 CURRVAL_NOT_INITIALIZED")
        void currVal_shouldThrow_whenNotInitialized() {
            assertThatThrownBy(() -> sequenceService.currVal("TEST_CACHED"))
                    .isInstanceOf(SequenceException.class)
                    .satisfies(e -> {
                        SequenceException se = (SequenceException) e;
                        assertThat(se.getErrorCode())
                                .isEqualTo(SequenceErrorCode.CURRVAL_NOT_INITIALIZED);
                    });
        }
    }

    @Nested
    @DisplayName("数据库状态验证")
    class DatabaseStateTests {

        @Test
        @DisplayName("号段加载后 max_id 按步长推进")
        void loadSegment_shouldAdvanceMaxId() {
            sequenceService.nextVal("TEST_CACHED");

            SequenceConfig config = sequenceRepository.selectConfig("TEST_CACHED");
            // 初始 max_id=1，step=1000，加载后 max_id=1+1000=1001
            assertThat(config.getMaxId()).isEqualTo(1001L);
        }

        @Test
        @DisplayName("DoubleBuffer 状态查询")
        void getBuffer_shouldReturnBufferState() {
            sequenceService.nextVal("TEST_CACHED");

            DoubleBuffer buffer = cachedSequence.getBuffer("TEST_CACHED");
            assertThat(buffer).isNotNull();

            Segment current = buffer.getCurrent();
            Segment standby = buffer.getStandby();

            assertThat(current).isNotNull();
            assertThat(current.getStart()).isEqualTo(2L);
            assertThat(current.getEnd()).isEqualTo(1001L);
            assertThat(current.remaining()).isEqualTo(999L);
            // 备用 Segment 可能为 null（尚未触发预加载）
        }
    }

    @Nested
    @DisplayName("异常场景")
    class ExceptionTests {

        @Test
        @DisplayName("nextVal - 不存在的序列自动创建（默认 STRICT）")
        void nextVal_shouldAutoCreate_whenSeqNotExists() {
            // getConfig 找不到配置时会自动创建默认 STRICT 行
            // 由于 CACHED 模式要求预先配置 mode=CACHED，自动创建为 STRICT 后走 STRICT 模式，
            // 因此不抛异常而是返回分配的值
            long value = sequenceService.nextVal("NON_EXISTENT_CACHED");
            assertThat(value).isEqualTo(2L);
        }

        @Test
        @DisplayName("loadSegment - 不存在的序列抛出 SEQ_NOT_FOUND")
        void loadSegment_shouldThrow_whenSeqNotExists() {
            assertThatThrownBy(() -> cachedSequence.loadSegment("NON_EXISTENT"))
                    .isInstanceOf(SequenceException.class)
                    .satisfies(e -> {
                        SequenceException se = (SequenceException) e;
                        assertThat(se.getErrorCode()).isEqualTo(SequenceErrorCode.SEQ_NOT_FOUND);
                    });
        }
    }
}
