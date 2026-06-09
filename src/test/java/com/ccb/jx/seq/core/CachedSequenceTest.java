
package com.ccb.jx.seq.core;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.buffer.EmptySegment;
import com.ccb.jx.seq.buffer.Segment;
import com.ccb.jx.seq.config.SequenceProperties;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.RecordStatus;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.model.SequenceRecord;
import com.ccb.jx.seq.repository.SequenceRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link CachedSequence} 的单元测试。
 * <p>
 * 验证号段缓存模式的 nextVal 逻辑，包括首次加载号段（S1: FOR UPDATE 原子性）、
 * 后续从内存分配、关闭拒绝、活跃号段统计（M3）、线程池配置（M4）等场景。
 * </p>
 *
 * @author XZJ
 */
@DisplayName("CachedSequence 号段缓存模式测试")
@ExtendWith(MockitoExtension.class)
class CachedSequenceTest {

    @Mock
    private SequenceRepository sequenceRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private CachedSequence cachedSequence;

    @BeforeEach
    void setUp() {
        cachedSequence = new CachedSequence(
                sequenceRepository, transactionManager, new SequenceProperties());
    }

    /**
     * 创建初始的 SequenceConfig（maxId=0，尚未分配任何号段）。
     */
    private SequenceConfig createInitialConfig(long step) {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setStep((int) step);
        config.setMaxId(0L);
        config.setMode(Mode.CACHED);
        config.setMinValue(1L);
        config.setMaxValue(Long.MAX_VALUE);
        return config;
    }

    @Test
    @DisplayName("S1: loadSegment 应使用 FOR UPDATE 保证号段加载原子性")
    void shouldUseForUpdateForAtomicSegmentLoad() {
        long step = 1000;
        SequenceConfig config = createInitialConfig(step);
        // config.getMaxId() = 0, so newMaxId = 0 + 1000 = 1000
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 1000L, 0L)).thenReturn(1);
        when(sequenceRepository.insertRecord(any(SequenceRecord.class))).thenReturn(1);

        long value = cachedSequence.nextVal("test_seq");
        // startValue = newMaxId - step + 1 = 1000 - 1000 + 1 = 1
        assertEquals(1L, value);

        verify(sequenceRepository).selectConfigForUpdate("test_seq");
        verify(sequenceRepository).updateMaxId("test_seq", 1000L, 0L);
    }

    @Test
    @DisplayName("S1: loadSegment 中 updateMaxId 失败应抛出 DB_ERROR")
    void shouldThrowDbErrorWhenUpdateMaxIdFails() {
        long step = 1000;
        SequenceConfig config = createInitialConfig(step);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 1000L, 0L)).thenReturn(0);

        SequenceException ex = assertThrows(SequenceException.class,
                () -> cachedSequence.nextVal("test_seq"));
        assertEquals(SequenceErrorCode.DB_ERROR, ex.getErrorCode());
    }

    @Test
    @DisplayName("后续调用 nextVal 应从内存分配，不再访问 DB")
    void shouldReturnFromBufferOnSubsequentCalls() {
        long step = 1000;
        SequenceConfig config = createInitialConfig(step);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 1000L, 0L)).thenReturn(1);
        when(sequenceRepository.insertRecord(any(SequenceRecord.class))).thenReturn(1);

        // 首次调用触发号段加载
        long firstVal = cachedSequence.nextVal("test_seq");
        assertEquals(1L, firstVal);

        // 后续调用应直接从 Buffer 分配
        long secondVal = cachedSequence.nextVal("test_seq");
        assertEquals(2L, secondVal);

        long thirdVal = cachedSequence.nextVal("test_seq");
        assertEquals(3L, thirdVal);

        // selectConfigForUpdate 应只被 loadSegment 调用一次
        verify(sequenceRepository, times(1)).selectConfigForUpdate("test_seq");
        verify(sequenceRepository, times(1)).updateMaxId("test_seq", 1000L, 0L);
    }

    @Test
    @DisplayName("nextVal 应记录号段分配记录")
    void shouldInsertRecordOnLoad() {
        long step = 1000;
        SequenceConfig config = createInitialConfig(step);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 1000L, 0L)).thenReturn(1);
        when(sequenceRepository.insertRecord(any(SequenceRecord.class))).thenReturn(1);

        cachedSequence.nextVal("test_seq");

        // 验证记录内容
        ArgumentCaptor<SequenceRecord> recordCaptor =
                ArgumentCaptor.forClass(SequenceRecord.class);
        verify(sequenceRepository).insertRecord(recordCaptor.capture());

        SequenceRecord record = recordCaptor.getValue();
        assertEquals("test_seq", record.getSeqName());
        assertEquals(Long.valueOf(1L), record.getStartValue());
        assertEquals(Long.valueOf(1000L), record.getEndValue());
        assertEquals(RecordStatus.ALLOCATED, record.getStatus());
        assertNotNull(record.getAllocTime());
    }

    @Test
    @DisplayName("序列不存在时应抛出 SEQ_NOT_FOUND")
    void shouldThrowWhenSequenceNotFound() {
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(null);

        SequenceException ex = assertThrows(SequenceException.class,
                () -> cachedSequence.nextVal("test_seq"));
        assertEquals(SequenceErrorCode.SEQ_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("关闭状态下 nextVal 应抛出 SHUTTING_DOWN")
    void shouldThrowWhenShuttingDown() {
        cachedSequence.transitionTo(ServiceState.SHUTTING_DOWN);

        SequenceException ex = assertThrows(SequenceException.class,
                () -> cachedSequence.nextVal("test_seq"));
        assertEquals(SequenceErrorCode.SHUTTING_DOWN, ex.getErrorCode());
    }

    @Test
    @DisplayName("getBuffer 应返回已加载的 DoubleBuffer")
    void shouldReturnLoadedBuffer() {
        long step = 1000;
        SequenceConfig config = createInitialConfig(step);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 1000L, 0L)).thenReturn(1);
        when(sequenceRepository.insertRecord(any(SequenceRecord.class))).thenReturn(1);

        cachedSequence.nextVal("test_seq");

        DoubleBuffer buffer = cachedSequence.getBuffer("test_seq");
        assertNotNull(buffer);
        assertNotNull(buffer.getCurrent());
        assertFalse(buffer.getCurrent() instanceof EmptySegment);
    }

    @Test
    @DisplayName("getBuffer 对未加载的序列应返回 null")
    void shouldReturnNullForUnloadedBuffer() {
        DoubleBuffer buffer = cachedSequence.getBuffer("unloaded_seq");
        assertNull(buffer);
    }

    @Test
    @DisplayName("persistUnusedSegments 在有关闭标志时应正常运行")
    void persistUnusedSegmentsShouldWork() {
        long step = 1000;
        SequenceConfig config = createInitialConfig(step);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 1000L, 0L)).thenReturn(1);
        when(sequenceRepository.insertRecord(any(SequenceRecord.class))).thenReturn(1);

        cachedSequence.nextVal("test_seq");

        // persistUnusedSegments 应正常运行，不抛异常
        assertDoesNotThrow(() -> cachedSequence.persistUnusedSegments());
    }

    @Test
    @DisplayName("M3: getActiveSegmentCount 应返回正确的活跃号段数")
    void shouldReturnCorrectActiveSegmentCount() {
        // 未加载任何序列时为 0
        assertEquals(0, cachedSequence.getActiveSegmentCount());

        // 加载一个序列
        long step = 1000;
        SequenceConfig config = createInitialConfig(step);
        when(sequenceRepository.selectConfigForUpdate("test_seq")).thenReturn(config);
        when(sequenceRepository.updateMaxId("test_seq", 1000L, 0L)).thenReturn(1);
        when(sequenceRepository.insertRecord(any(SequenceRecord.class))).thenReturn(1);

        cachedSequence.nextVal("test_seq");
        // 至少有 1 个活跃号段（current）
        assertTrue(cachedSequence.getActiveSegmentCount() >= 1);
    }

    @Test
    @DisplayName("M4: 异步预加载线程池核心线程数应为 20")
    void shouldHaveCorePoolSizeOfTwenty() {
        java.util.concurrent.ExecutorService executor = cachedSequence.getAsyncExecutor();
        assertTrue(executor instanceof ThreadPoolExecutor);
        assertEquals(20, ((ThreadPoolExecutor) executor).getCorePoolSize());
    }
}
