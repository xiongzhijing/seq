
package com.ccb.jx.seq.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SequenceConfig} 的单元测试。
 *
 * @author XZJ
 */
@DisplayName("SequenceConfig 配置模型测试")
class SequenceConfigTest {

    @Test
    @DisplayName("无参构造器应设置合理的默认值")
    void shouldHaveDefaultValues() {
        SequenceConfig config = new SequenceConfig();

        assertNull(config.getSeqName());
        assertEquals(Long.valueOf(1L), config.getMaxId());
        assertEquals(Integer.valueOf(1000), config.getStep());
        assertEquals(Integer.valueOf(1), config.getIncrementBy());
        assertEquals(Long.valueOf(1L), config.getMinValue());
        assertEquals(Long.valueOf(Long.MAX_VALUE), config.getMaxValue());
        assertEquals(Integer.valueOf(0), config.getCycle());
        assertEquals(Mode.CACHED, config.getMode());
        assertEquals(Long.valueOf(1L), config.getStartWith());
        assertNull(config.getDescription());
        assertNull(config.getUpdateTime());
        assertNull(config.getCreateTime());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 seqName")
    void shouldSetAndGetSeqName() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");

        assertEquals("test_seq", config.getSeqName());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 maxId")
    void shouldSetAndGetMaxId() {
        SequenceConfig config = new SequenceConfig();
        config.setMaxId(500L);

        assertEquals(Long.valueOf(500L), config.getMaxId());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 step")
    void shouldSetAndGetStep() {
        SequenceConfig config = new SequenceConfig();
        config.setStep(2000);

        assertEquals(Integer.valueOf(2000), config.getStep());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 incrementBy")
    void shouldSetAndGetIncrementBy() {
        SequenceConfig config = new SequenceConfig();
        config.setIncrementBy(2);

        assertEquals(Integer.valueOf(2), config.getIncrementBy());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 mode")
    void shouldSetAndGetMode() {
        SequenceConfig config = new SequenceConfig();
        config.setMode(Mode.STRICT);

        assertEquals(Mode.STRICT, config.getMode());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 cycle")
    void shouldSetAndGetCycle() {
        SequenceConfig config = new SequenceConfig();
        config.setCycle(1);

        assertEquals(Integer.valueOf(1), config.getCycle());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 description")
    void shouldSetAndGetDescription() {
        SequenceConfig config = new SequenceConfig();
        config.setDescription("测试序列");

        assertEquals("测试序列", config.getDescription());
    }

    @Test
    @DisplayName("setter/getter 应正确读写时间字段")
    void shouldSetAndGetTimeFields() {
        SequenceConfig config = new SequenceConfig();
        Date now = new Date();
        config.setUpdateTime(now);
        config.setCreateTime(now);

        assertSame(now, config.getUpdateTime());
        assertSame(now, config.getCreateTime());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 minValue 和 maxValue")
    void shouldSetAndGetMinMaxValue() {
        SequenceConfig config = new SequenceConfig();
        config.setMinValue(10L);
        config.setMaxValue(1000L);

        assertEquals(Long.valueOf(10L), config.getMinValue());
        assertEquals(Long.valueOf(1000L), config.getMaxValue());
    }

    @Test
    @DisplayName("setter/getter 应正确读写 startWith")
    void shouldSetAndGetStartWith() {
        SequenceConfig config = new SequenceConfig();
        config.setStartWith(100L);

        assertEquals(Long.valueOf(100L), config.getStartWith());
    }

    @Test
    @DisplayName("equals 应基于 seqName 比较")
    void equalsShouldBeBasedOnSeqName() {
        SequenceConfig config1 = new SequenceConfig();
        config1.setSeqName("test_seq");
        config1.setStep(1000);

        SequenceConfig config2 = new SequenceConfig();
        config2.setSeqName("test_seq");
        config2.setStep(2000);

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
    }

    @Test
    @DisplayName("不同 seqName 的 config 应不相等")
    void differentSeqNameShouldNotBeEqual() {
        SequenceConfig config1 = new SequenceConfig();
        config1.setSeqName("seq_a");

        SequenceConfig config2 = new SequenceConfig();
        config2.setSeqName("seq_b");

        assertNotEquals(config1, config2);
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void toStringShouldContainKeyFields() {
        SequenceConfig config = new SequenceConfig();
        config.setSeqName("test_seq");
        String str = config.toString();

        assertTrue(str.contains("seqName="));
        assertTrue(str.contains("maxId="));
        assertTrue(str.contains("mode="));
    }

    // ==================== Builder 测试 ====================

    @Test
    @DisplayName("Builder 应创建带 seqName 的配置")
    void builderShouldCreateConfigWithSeqName() {
        SequenceConfig config = SequenceConfig.builder("my_seq").build();

        assertEquals("my_seq", config.getSeqName());
    }

    @Test
    @DisplayName("Builder 应使用合理的默认值")
    void builderShouldHaveReasonableDefaults() {
        SequenceConfig config = SequenceConfig.builder("test_seq").build();

        assertEquals("test_seq", config.getSeqName());
        assertEquals(Long.valueOf(1L), config.getMaxId());
        assertEquals(Integer.valueOf(1000), config.getStep());
        assertEquals(Integer.valueOf(1), config.getIncrementBy());
        assertEquals(Long.valueOf(1L), config.getMinValue());
        assertEquals(Long.valueOf(Long.MAX_VALUE), config.getMaxValue());
        assertEquals(Integer.valueOf(0), config.getCycle());
        assertEquals(Mode.CACHED, config.getMode());
        assertEquals(Long.valueOf(1L), config.getStartWith());
        assertNull(config.getDescription());
    }

    @Test
    @DisplayName("Builder 应支持自定义所有字段")
    void builderShouldSupportAllFields() {
        SequenceConfig config = SequenceConfig.builder("custom_seq")
                .maxId(100L)
                .step(2000)
                .incrementBy(5)
                .minValue(10L)
                .maxValue(100000L)
                .cycle(1)
                .mode(Mode.STRICT)
                .startWith(50L)
                .description("自定义序列")
                .build();

        assertEquals("custom_seq", config.getSeqName());
        assertEquals(Long.valueOf(100L), config.getMaxId());
        assertEquals(Integer.valueOf(2000), config.getStep());
        assertEquals(Integer.valueOf(5), config.getIncrementBy());
        assertEquals(Long.valueOf(10L), config.getMinValue());
        assertEquals(Long.valueOf(100000L), config.getMaxValue());
        assertEquals(Integer.valueOf(1), config.getCycle());
        assertEquals(Mode.STRICT, config.getMode());
        assertEquals(Long.valueOf(50L), config.getStartWith());
        assertEquals("自定义序列", config.getDescription());
    }

    @Test
    @DisplayName("Builder 创建的配置应与 setter 创建的配置行为一致")
    void builderShouldBeConsistentWithSetter() {
        SequenceConfig builtConfig = SequenceConfig.builder("test_seq")
                .mode(Mode.STRICT)
                .step(500)
                .startWith(100L)
                .build();

        SequenceConfig setterConfig = new SequenceConfig();
        setterConfig.setSeqName("test_seq");
        setterConfig.setMode(Mode.STRICT);
        setterConfig.setStep(500);
        setterConfig.setStartWith(100L);

        assertEquals(builtConfig.getSeqName(), setterConfig.getSeqName());
        assertEquals(builtConfig.getMode(), setterConfig.getMode());
        assertEquals(builtConfig.getStep(), setterConfig.getStep());
        assertEquals(builtConfig.getStartWith(), setterConfig.getStartWith());
    }
}
