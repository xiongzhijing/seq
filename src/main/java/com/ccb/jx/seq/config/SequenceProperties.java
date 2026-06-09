
package com.ccb.jx.seq.config;

import com.ccb.jx.seq.model.SequenceDialect;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConstructorBinding;

/**
 * 序列服务配置属性（不可变）。
 * <p>
 * 绑定 {@code sequence.*} 前缀的配置项，支持以下配置块：
 * <ul>
 *   <li>{@code sequence.datasource.*} — 独立数据源配置（可选），用于隔离序列操作的数据库连接</li>
 *   <li>{@code sequence.instance-id} — 实例标识（可选），默认自动生成为 {@code ip:port} 格式</li>
 * </ul>
 * <p>
 * 使用 {@link ConstructorBinding} 实现不可变配置，所有字段为 {@code final}，
 * 符合项目规范"Immutable config POJOs — @ConfigurationProperties with final fields.
 * No builders, no runtime setters"。
 *
 * @author XZJ
 */
@ConfigurationProperties(prefix = "sequence")
@ConstructorBinding
public class SequenceProperties {

    /** 独立数据源配置 */
    private final Datasource datasource;

    /** 实例 ID（可选，默认自动生成 ip:port） */
    private final String instanceId;

    /** 配置缓存 TTL（分钟），每隔此时间自动清空配置缓存以感知 DB 配置变更，默认 5 分钟 */
    private final long configCacheTtlMinutes;

    /** 原生 Sequence 方言，默认 NONE（即不使用原生 Sequence，走 FOR UPDATE） */
    private final SequenceDialect nativeSequenceDialect;

    /** 是否启用序列监控接口，默认 false（禁用）。启用后注册 /sequence/* REST 端点 */
    private final boolean controllerEnabled;

    /**
     * 构造不可变配置属性。
     *
     * @param datasource                独立数据源配置，默认为空数据源配置
     * @param instanceId                实例标识，默认为 {@code null}（自动生成）
     * @param configCacheTtlMinutes     配置缓存 TTL（分钟），默认 5 分钟
     * @param nativeSequenceDialect     原生 Sequence 方言，默认 NONE
     * @param controllerEnabled         是否启用监控接口，默认 false
     */
    public SequenceProperties(Datasource datasource,
                              String instanceId,
                              long configCacheTtlMinutes,
                              SequenceDialect nativeSequenceDialect,
                              boolean controllerEnabled) {
        this.datasource = datasource != null ? datasource : new Datasource();
        this.instanceId = instanceId;
        this.configCacheTtlMinutes = configCacheTtlMinutes;
        this.nativeSequenceDialect = nativeSequenceDialect != null
                ? nativeSequenceDialect : SequenceDialect.NONE;
        this.controllerEnabled = controllerEnabled;
    }

    /**
     * 默认构造器（Spring Boot 2.x 回退使用）。
     * <p>
     * 当 Spring 无法通过构造器绑定（如缺少 {@code @ConstructorBinding} 支持）时，
     * 回退到 setter 绑定模式。此构造器提供所有字段的默认值。
     * </p>
     */
    public SequenceProperties() {
        this.datasource = new Datasource();
        this.instanceId = null;
        this.configCacheTtlMinutes = 5;
        this.nativeSequenceDialect = SequenceDialect.NONE;
        this.controllerEnabled = false;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public long getConfigCacheTtlMinutes() {
        return configCacheTtlMinutes;
    }

    public SequenceDialect getNativeSequenceDialect() {
        return nativeSequenceDialect;
    }

    public boolean isControllerEnabled() {
        return controllerEnabled;
    }

    /**
     * 独立数据源配置（不可变）。
     * <p>
     * 当配置 {@code sequence.datasource.url} 时，自动创建独立于业务数据库的
     * {@link javax.sql.DataSource} 用于序列操作。支持 Spring Boot 2.x 的
     * {@code jdbc-url} 和标准 {@code url} 属性。
     */
    @ConstructorBinding
    public static class Datasource {

        /** JDBC URL（标准属性） */
        private final String url;

        /** JDBC 驱动类名，默认 MySQL 8.x 驱动 */
        private final String driverClassName;

        /** 数据库用户名 */
        private final String username;

        /** 数据库密码 */
        private final String password;

        /** JDBC URL（兼容 Spring Boot 2.x jdbc-url 属性） */
        private final String jdbcUrl;

        /**
         * 构造不可变数据源配置。
         *
         * @param url             JDBC URL（标准属性），默认 {@code null}
         * @param driverClassName  JDBC 驱动类名，默认 {@code com.mysql.cj.jdbc.Driver}
         * @param username         数据库用户名，默认 {@code null}
         * @param password         数据库密码，默认 {@code null}
         * @param jdbcUrl          JDBC URL（兼容属性），默认 {@code null}
         */
        public Datasource(String url, String driverClassName, String username,
                          String password, String jdbcUrl) {
            this.url = url;
            this.driverClassName = driverClassName != null ? driverClassName : "com.mysql.cj.jdbc.Driver";
            this.username = username;
            this.password = password;
            this.jdbcUrl = jdbcUrl;
        }

        /**
         * 默认构造器。
         */
        public Datasource() {
            this.url = null;
            this.driverClassName = "com.mysql.cj.jdbc.Driver";
            this.username = null;
            this.password = null;
            this.jdbcUrl = null;
        }

        public String getUrl() {
            return url;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        /**
         * 获取有效的 JDBC URL。
         * <p>
         * 优先返回 {@link #jdbcUrl}（兼容 Spring Boot 2.x 的 {@code jdbc-url} 属性名），
         * 若未配置则回退到 {@link #url}。
         *
         * @return 有效的 JDBC URL，可能为 {@code null}
         */
        public String getEffectiveUrl() {
            return jdbcUrl != null ? jdbcUrl : url;
        }
    }

}
