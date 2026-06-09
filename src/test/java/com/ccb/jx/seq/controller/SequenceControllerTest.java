
package com.ccb.jx.seq.controller;

import com.ccb.jx.seq.buffer.DoubleBuffer;
import com.ccb.jx.seq.buffer.Segment;
import com.ccb.jx.seq.core.SequenceService;
import com.ccb.jx.seq.exception.SequenceErrorCode;
import com.ccb.jx.seq.exception.SequenceException;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("M7: SequenceController 增强测试")
@ExtendWith(MockitoExtension.class)
class SequenceControllerTest {

    @Mock
    private SequenceService sequenceService;

    private SequenceController controller;

    @BeforeEach
    void setUp() {
        controller = new SequenceController(sequenceService);
    }

    @Test
    @DisplayName("getSequenceInfo 应返回 SequenceInfoVO")
    void shouldReturnSequenceInfoVO() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        config.setMode(Mode.STRICT);
        config.setMaxId(100L);

        SequenceService.SequenceInfo info = new SequenceService.SequenceInfo(
                config, 0, false, null);
        when(sequenceService.getSequenceInfo("test_seq")).thenReturn(info);

        SequenceInfoVO result = controller.getSequenceInfo("test_seq");

        assertNotNull(result);
        assertEquals("test_seq", result.getSeqName());
        assertEquals("STRICT", result.getMode());
    }

    @Test
    @DisplayName("序列名称超过 64 字符时应抛出异常")
    void shouldThrowWhenSeqNameExceedsMaxLength() {
        String longName = String.join("", Collections.nCopies(65, "a"));

        SequenceException ex = assertThrows(SequenceException.class,
                () -> controller.getSequenceInfo(longName));
        assertEquals(SequenceErrorCode.INVALID_CONFIG, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("maximum length"));
    }

    @Test
    @DisplayName("序列名称为 64 字符时应正常处理")
    void shouldAcceptSeqNameAtMaxLength() {
        String maxName = String.join("", Collections.nCopies(64, "a"));

        SequenceConfig config = new SequenceConfig();
        config.setSeqName(maxName);
        config.setMode(Mode.STRICT);
        config.setMaxId(1L);

        SequenceService.SequenceInfo info = new SequenceService.SequenceInfo(
                config, 0, false, null);
        when(sequenceService.getSequenceInfo(maxName)).thenReturn(info);

        SequenceInfoVO result = controller.getSequenceInfo(maxName);
        assertNotNull(result);
    }

    @Test
    @DisplayName("CACHED 模式应从 DoubleBuffer 提取 currentValue 和 totalCapacity")
    void shouldExtractBufferInfoForCachedMode() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("cached_seq");
        config.setMode(Mode.CACHED);
        config.setMaxId(1000L);

        // Create a Segment with range [1, 1000]
        Segment segment = new Segment(1, 1000);
        // Consume one value to advance current to 2
        segment.next();

        DoubleBuffer mockBuffer = mock(DoubleBuffer.class);
        when(mockBuffer.getCurrent()).thenReturn(segment);

        SequenceService.SequenceInfo info = new SequenceService.SequenceInfo(
                config, 0, false, mockBuffer);
        when(sequenceService.getSequenceInfo("cached_seq")).thenReturn(info);

        SequenceInfoVO result = controller.getSequenceInfo("cached_seq");

        assertEquals("cached_seq", result.getSeqName());
        assertEquals("CACHED", result.getMode());
        // totalCapacity = end - start + 1 = 1000 - 1 + 1 = 1000
        assertEquals(1000L, result.getTotalCapacity());
        // currentValue = current.getCurrent() - 1 = 2 - 1 = 1
        assertEquals(1L, result.getCurrentValue());
    }

    @Test
    @DisplayName("STRICT 模式下 totalCapacity 应为 0")
    void shouldReturnZeroTotalCapacityForStrictMode() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("strict_seq");
        config.setMode(Mode.STRICT);
        config.setMaxId(500L);

        SequenceService.SequenceInfo info = new SequenceService.SequenceInfo(
                config, 0, false, null);
        when(sequenceService.getSequenceInfo("strict_seq")).thenReturn(info);

        SequenceInfoVO result = controller.getSequenceInfo("strict_seq");

        assertEquals(0L, result.getTotalCapacity());
        assertEquals(500L, result.getCurrentValue());
    }
}
