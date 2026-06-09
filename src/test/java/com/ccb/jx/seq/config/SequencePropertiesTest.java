
package com.ccb.jx.seq.config;

import com.ccb.jx.seq.model.SequenceDialect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("M5: SequenceProperties 不可变配置测试")
class SequencePropertiesTest {

    @Test
    @DisplayName("默认构造器应提供正确的默认值")
    void shouldProvideDefaultValuesWithDefaultConstructor() {
        SequenceProperties props = new SequenceProperties();
        assertNotNull(props.getDatasource());
        assertNull(props.getInstanceId());
        assertEquals(5L, props.getConfigCacheTtlMinutes());
    }

    @Test
    @DisplayName("全参构造器应正确设置所有字段")
    void shouldSetAllFieldsWithFullConstructor() {
        SequenceProperties.Datasource ds = new SequenceProperties.Datasource(
                "jdbc:mysql://localhost:3306/seq", "com.mysql.cj.jdbc.Driver",
                "root", "pass", null);

        SequenceProperties props = new SequenceProperties(ds, "10.0.0.1:8080", 10L,
                SequenceDialect.NONE, true);

        assertEquals("jdbc:mysql://localhost:3306/seq", props.getDatasource().getUrl());
        assertEquals("10.0.0.1:8080", props.getInstanceId());
        assertEquals(10L, props.getConfigCacheTtlMinutes());
    }

    @Test
    @DisplayName("Datasource 不可变 — 字段应为 final")
    void datasourceShouldBeImmutable() {
        SequenceProperties.Datasource ds = new SequenceProperties.Datasource(
                "jdbc:mysql://localhost/seq", "com.mysql.cj.jdbc.Driver",
                "user", "pass", "jdbc:mysql://localhost/seq2");

        assertEquals("jdbc:mysql://localhost/seq", ds.getUrl());
        assertEquals("com.mysql.cj.jdbc.Driver", ds.getDriverClassName());
        assertEquals("user", ds.getUsername());
        assertEquals("pass", ds.getPassword());
        assertEquals("jdbc:mysql://localhost/seq2", ds.getJdbcUrl());
        // getEffectiveUrl should return jdbcUrl when set
        assertEquals("jdbc:mysql://localhost/seq2", ds.getEffectiveUrl());
    }

    @Test
    @DisplayName("Datasource getEffectiveUrl 应优先返回 jdbcUrl")
    void shouldPreferJdbcUrlOverUrl() {
        SequenceProperties.Datasource ds = new SequenceProperties.Datasource(
                "url-value", null, null, null, "jdbcUrl-value");
        assertEquals("jdbcUrl-value", ds.getEffectiveUrl());
    }

    @Test
    @DisplayName("Datasource getEffectiveUrl 在 jdbcUrl 为 null 时应回退到 url")
    void shouldFallbackToUrlWhenJdbcUrlIsNull() {
        SequenceProperties.Datasource ds = new SequenceProperties.Datasource(
                "url-value", null, null, null, null);
        assertEquals("url-value", ds.getEffectiveUrl());
    }

    @Test
    @DisplayName("Datasource 默认驱动应为 com.mysql.cj.jdbc.Driver")
    void shouldDefaultToMysqlDriver() {
        SequenceProperties.Datasource ds = new SequenceProperties.Datasource(
                null, null, null, null, null);
        assertEquals("com.mysql.cj.jdbc.Driver", ds.getDriverClassName());
    }

    @Test
    @DisplayName("null datasource 应替换为默认 Datasource")
    void shouldReplaceNullDatasourceWithDefault() {
        SequenceProperties props = new SequenceProperties(null, "10.0.0.1:8080", 5L,
                SequenceDialect.NONE, true);
        assertNotNull(props.getDatasource());
    }
}
