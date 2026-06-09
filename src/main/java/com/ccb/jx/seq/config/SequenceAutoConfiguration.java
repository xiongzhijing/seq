
package com.ccb.jx.seq.config;

import com.ccb.jx.seq.core.CachedSequence;
import com.ccb.jx.seq.core.NativeStrictSequence;
import com.ccb.jx.seq.core.SequenceStrategy;
import com.ccb.jx.seq.core.SequenceService;
import com.ccb.jx.seq.core.StrictSequence;
import com.ccb.jx.seq.controller.SequenceController;
import com.ccb.jx.seq.dialect.NativeSequenceDialect;
import com.ccb.jx.seq.dialect.NativeSequenceSynchronizer;
import com.ccb.jx.seq.dialect.TdsqlSequenceDialect;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceDialect;
import com.ccb.jx.seq.repository.SequenceRepository;
import com.ccb.jx.seq.session.SessionHolder;
import com.ccb.jx.seq.session.SessionHolderCleanupInterceptor;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperScannerConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 序列服务自动配置。
 * <p>
 * 为宿主应用自动装配完整的序列服务基础设施，包括：
 * <ul>
 *   <li>独立数据源（通过 {@code sequence.datasource.*} 配置）</li>
 *   <li>MyBatis 映射器扫描（{@link SequenceRepository}）</li>
 *   <li>严格连续模式（{@link StrictSequence}）与号段缓存模式（{@link CachedSequence}）生成器</li>
 *   <li>统一入口服务（{@link SequenceService}）</li>
 *   <li>优雅关闭（{@link SequenceShutdownHook}）</li>
 * </ul>
 * <p>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 注册，Spring Boot 启动时自动加载。
 *
 * @author XZJ
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(SequenceProperties.class)
public class SequenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SequenceAutoConfiguration.class);

    private final SequenceProperties properties;

    /**
     * 构造自动配置。
     *
     * @param properties 序列服务配置属性，通过 {@link EnableConfigurationProperties} 注入
     */
    public SequenceAutoConfiguration(SequenceProperties properties) {
        this.properties = properties;
    }

    // ========================================================================
    // 1. 序列数据源配置（支持 fallback 到应用数据源）
    // ========================================================================

    /**
     * 序列数据源配置。
     * <p>
     * 始终加载，通过 {@link #sequenceDataSource} 方法内部实现 fallback 逻辑：
     * <ul>
     *   <li>若 {@code sequence.datasource.url} 或 {@code sequence.datasource.jdbc-url} 已配置，
     *       创建独立 HikariCP 数据源</li>
     *   <li>若均未配置，fallback 到应用自带的 DataSource</li>
     * </ul>
     * <p>
     * 使用内部 {@code @Configuration} 类保证条件注解只作用于此组 Bean，
     * 不影响后续无状态 Bean 的定义。
     */
    @Configuration(proxyBeanMethods = false)
    static class SequenceDataSourceConfiguration {

        private static final Logger dsLog = LoggerFactory.getLogger(SequenceDataSourceConfiguration.class);

        /**
         * 序列服务数据源（支持 fallback 到应用数据源）。
         * <p>
         * 优先使用独立数据源（通过 {@code sequence.datasource.*} 配置），
         * 若未配置则 fallback 到应用自带的 DataSource，简化配置。
         * <p>
         * Fallback 模式下使用 {@link SharedDataSourceWrapper} 包装应用 DataSource，
         * 防止 Spring 容器关闭时误关闭应用数据源。
         *
         * @param properties         序列服务配置属性
         * @param dataSourceProvider 应用 DataSource 的懒注入提供者（仅在 fallback 时解析）
         */
        @Bean
        DataSource sequenceDataSource(SequenceProperties properties,
                                      ObjectProvider<DataSource> dataSourceProvider) {
            SequenceProperties.Datasource dsConfig = properties.getDatasource();
            String url = dsConfig.getEffectiveUrl();
            if (url != null && !url.isEmpty()) {
                // 独立数据源：创建 HikariCP 连接池
                HikariConfig hikariConfig = new HikariConfig();
                hikariConfig.setJdbcUrl(url);
                hikariConfig.setDriverClassName(dsConfig.getDriverClassName());
                hikariConfig.setUsername(dsConfig.getUsername());
                hikariConfig.setPassword(dsConfig.getPassword());
                hikariConfig.setMaximumPoolSize(5);
                hikariConfig.setMinimumIdle(1);
                hikariConfig.setConnectionTimeout(3000);
                hikariConfig.setMaxLifetime(1800000);
                hikariConfig.setPoolName("sequence-hikari-pool");

                dsLog.info("Creating independent sequence DataSource with HikariCP: url={}, driverClassName={}, poolName={}",
                        url, dsConfig.getDriverClassName(), hikariConfig.getPoolName());
                return new HikariDataSource(hikariConfig);
            }

            // Fallback：使用应用 DataSource
            DataSource appDataSource = dataSourceProvider.getIfAvailable();
            if (appDataSource == null) {
                throw new IllegalStateException(
                        "No sequence.datasource.url configured and no application DataSource available. "
                                + "Please configure either sequence.datasource.url or spring.datasource.url.");
            }
            dsLog.info("Falling back to application DataSource for sequence operations: {}",
                    appDataSource.getClass().getSimpleName());
            return new SharedDataSourceWrapper(appDataSource);
        }

        /**
         * 序列服务事务管理器。
         * <p>
         * 关联 {@link #sequenceDataSource()}，用于 {@link StrictSequence#nextVal(String)}
         * 的 {@code REQUIRES_NEW} 独立事务和 {@link CachedSequence#loadSegment(String)}
         * 的号段加载事务。
         */
        @Bean
        DataSourceTransactionManager sequenceTransactionManager(DataSource sequenceDataSource) {
            dsLog.debug("Creating sequence TransactionManager");
            return new DataSourceTransactionManager(sequenceDataSource);
        }

        /**
         * 序列服务 MyBatis SqlSessionFactory。
         * <p>
         * 绑定到 {@link #sequenceDataSource()}，设置 Mapper XML 路径和
         * 类型别名包，启用下划线到驼峰的自动映射。
         */
        @Bean
        SqlSessionFactory sequenceSqlSessionFactory(DataSource sequenceDataSource) throws Exception {
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(sequenceDataSource);
            factoryBean.setTypeAliasesPackage("com.ccb.jx.seq.model");

            // 设置 Mapper XML 路径（打包在 starter jar 内的映射文件）
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            factoryBean.setMapperLocations(
                    resolver.getResources("classpath*:com/ccb/jx/seq/repository/*.xml")
            );

            org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            factoryBean.setConfiguration(config);

            dsLog.debug("Creating sequence SqlSessionFactory with mapper locations: classpath*:com/ccb/jx/seq/repository/*.xml");
            return factoryBean.getObject();
        }

        /**
         * 序列服务 Mapper 扫描器。
         * <p>
         * 扫描 {@link SequenceRepository} 所在包，将映射器接口注册到
         * {@link #sequenceSqlSessionFactory(DataSource)}。
         * 使用 {@code sqlSessionFactoryBeanName} 字符串引用而非直接依赖，
         * 避免 BeanFactoryPostProcessor 与普通 Bean 的加载顺序冲突。
         */
        @Bean
        MapperScannerConfigurer sequenceMapperScanner() {
            MapperScannerConfigurer scanner = new MapperScannerConfigurer();
            scanner.setBasePackage("com.ccb.jx.seq.repository");
            scanner.setSqlSessionFactoryBeanName("sequenceSqlSessionFactory");
            dsLog.debug("Creating sequence MapperScannerConfigurer for package: com.ccb.jx.seq.repository");
            return scanner;
        }

        /**
         * 序列数据源的 JdbcTemplate。
         * <p>
         * 用于 {@link NativeSequenceDialect} 执行原生 Sequence 操作
         * （如 {@code SELECT tdsql_nextval()}）。
         * </p>
         */
        @Bean
        JdbcTemplate sequenceJdbcTemplate(DataSource sequenceDataSource) {
            dsLog.debug("Creating sequence JdbcTemplate");
            return new JdbcTemplate(sequenceDataSource);
        }

        // SequenceProperties 通过 @Bean 方法参数注入（参见 sequenceDataSource(SequenceProperties)）
        // 静态内部类无需引用外部类实例字段，Spring 容器直接按类型注入
    }

    /**
     * 不可关闭的 DataSource 包装器。
     * <p>
     * Fallback 模式下包装应用的 DataSource，防止 Spring 容器关闭时
     * 误关闭应用数据源。独立 HikariCP 数据源实现了 {@code Closeable}，
     * Spring 会自动推断并调用 {@code close()}；而应用数据源应由应用自身管理生命周期。
     * <p>
     * 此包装器继承 {@link AbstractDataSource}，不实现 {@code Closeable}，
     * Spring 不会为其推断 destroyMethod。
     */
    static class SharedDataSourceWrapper extends AbstractDataSource {

        private final DataSource delegate;

        SharedDataSourceWrapper(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return delegate.getConnection(username, password);
        }
    }

    // ========================================================================
    // 2. 序列服务核心 Bean
    // ========================================================================

    /**
     * 严格连续模式序列生成器（FOR UPDATE 模式）。
     * <p>
     * 使用 {@code SELECT ... FOR UPDATE} 悲观锁串行化分配，
     * 确保每次 {@code nextVal} 返回值严格连续、不跳号。
     * 适用于低并发、高连续性要求的场景。
     * <p>
     * 仅当 {@code sequence.native-sequence-dialect=NONE}（默认）时激活。
     *
     * @param sequenceRepository 序列数据访问层
     * @return StrictSequence 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "sequence", name = "native-sequence-dialect",
            havingValue = "NONE", matchIfMissing = true)
    SequenceStrategy strictSequence(SequenceRepository sequenceRepository,
                                   PlatformTransactionManager sequenceTransactionManager) {
        log.debug("Creating StrictSequence bean (FOR UPDATE mode)");
        return new StrictSequence(sequenceRepository, sequenceTransactionManager);
    }

    /**
     * TDSQL 原生 Sequence 方言实现。
     * <p>
     * 仅当 {@code sequence.native-sequence-dialect=TDSQL} 时激活。
     *
     * @param sequenceJdbcTemplate 序列数据源的 JdbcTemplate
     * @return TdsqlSequenceDialect 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "sequence", name = "native-sequence-dialect",
            havingValue = "TDSQL")
    NativeSequenceDialect tdsqlSequenceDialect(JdbcTemplate sequenceJdbcTemplate) {
        log.debug("Creating TdsqlSequenceDialect bean");
        return new TdsqlSequenceDialect(sequenceJdbcTemplate);
    }

    /**
     * 号段缓存模式序列生成器。
     * <p>
     * 使用双 Buffer 机制批量预取号段，实现高性能无锁分配。
     * 适用于高并发、可容忍少量号段空洞的场景。
     *
     * @param sequenceRepository 序列数据访问层
     * @return CachedSequence 实例
     */
    @Bean
    @ConditionalOnMissingBean
    CachedSequence cachedSequence(SequenceRepository sequenceRepository,
                                   PlatformTransactionManager sequenceTransactionManager,
                                   SequenceProperties properties) {
        log.debug("Creating CachedSequence bean");
        return new CachedSequence(
                sequenceRepository,
                sequenceTransactionManager,
                properties
        );
    }

    /**
     * 原生 Sequence 严格连续模式生成器。
     * <p>
     * 委托数据库原生 Sequence 对象生成值，消除应用层行锁。
     * 在 Bean 创建时同步 {@code sequence_config} 中所有 {@code mode=STRICT}
     * 的序列到 DB Sequence 对象。
     *
     * @param dialect    NativeSequenceDialect 实例
     * @param repository 序列数据访问层
     * @return NativeStrictSequence 实例
     */
    @Bean
    @ConditionalOnBean(NativeSequenceDialect.class)
    SequenceStrategy nativeStrictSequence(NativeSequenceDialect dialect,
                                  SequenceRepository repository) {
        log.debug("Creating NativeStrictSequence bean with dialect: {}", dialect.getClass().getSimpleName());
        // 在 Bean 创建时直接调用同步，无需等待初始化回调
        NativeSequenceSynchronizer synchronizer = new NativeSequenceSynchronizer(repository, dialect);
        synchronizer.syncOnStartup();
        log.info("Native sequence sync completed on startup");
        return new NativeStrictSequence(dialect, repository);
    }

    /**
     * 统一序列服务入口。
     * <p>
     * 根据序列配置的 {@code mode}（STRICT / CACHED）通过策略映射路由到对应的
     * {@link SequenceStrategy} 实现。
     * <p>
     * 配置缓存通过定时调度器自动刷新（默认每 5 分钟清空），
     * Spring 容器关闭时通过 {@code destroyMethod="stop"} 优雅关闭调度器。
     *
     * @param strictStrategy     严格连续模式生成器（FOR UPDATE 或原生）
     * @param cachedSequence     号段缓存模式生成器
     * @param sequenceRepository 序列数据访问层
     * @return SequenceService 实例
     */
    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean
    SequenceService sequenceService(SequenceStrategy strictStrategy,
                                     CachedSequence cachedSequence,
                                     SequenceRepository sequenceRepository) {
        long ttlMinutes = properties.getConfigCacheTtlMinutes();
        log.debug("Creating SequenceService bean, configCacheTtlMinutes={}", ttlMinutes);

        Map<Mode, SequenceStrategy> strategyMap = new HashMap<>();
        strategyMap.put(Mode.STRICT, strictStrategy);
        strategyMap.put(Mode.CACHED, cachedSequence);

        return new SequenceService(strategyMap, sequenceRepository, ttlMinutes);
    }

    /**
     * 优雅关闭钩子。
     * <p>
     * 在 Spring 上下文关闭事件触发时，持久化未使用号段信息并清理资源。
     * 注册为普通 Bean（非 {@code @PreDestroy}），依赖
     * {@link SequenceShutdownHook} 内部的生命周期管理。
     *
     * @param cachedSequence       号段缓存模式生成器
     * @return SequenceShutdownHook 实例
     */
    @Bean
    @ConditionalOnMissingBean
    SequenceShutdownHook sequenceShutdownHook(CachedSequence cachedSequence) {
        log.debug("Creating SequenceShutdownHook bean");
        return new SequenceShutdownHook(cachedSequence);
    }

    // ========================================================================
    // 4. 序列监控接口（条件加载，默认禁用）
    // ========================================================================

    /**
     * 序列监控 REST 控制器。
     * <p>
     * 仅当 {@code sequence.controller-enabled=true} 时注册，
     * 提供 {@code GET /sequence/{seqName}} 和 {@code GET /sequence/list} 只读端点。
     * 默认禁用，避免在生产环境意外暴露内部状态。
     *
     * @param sequenceService 序列核心服务
     * @return SequenceController 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "sequence", name = "controller-enabled", havingValue = "true")
    @ConditionalOnMissingBean
    SequenceController sequenceController(SequenceService sequenceService) {
        log.debug("Creating SequenceController bean (monitoring API enabled)");
        return new SequenceController(sequenceService);
    }

    // ========================================================================
    // 3. SessionHolder 自动清理拦截器
    // ========================================================================

    /**
     * SessionHolder 清理拦截器。
     * <p>
     * 在每个 HTTP 请求完成后自动清理 {@link SessionHolder}
     * 中的 {@link ThreadLocal} 数据，防止线程池复用场景下的内存泄漏。
     *
     * @return SessionHolderCleanupInterceptor 实例
     */
    @Bean
    @ConditionalOnMissingBean
    SessionHolderCleanupInterceptor sessionHolderCleanupInterceptor() {
        log.debug("Creating SessionHolderCleanupInterceptor bean");
        return new SessionHolderCleanupInterceptor();
    }

    /**
     * 注册 SessionHolder 清理拦截器到 Spring MVC 拦截器链。
     * <p>
     * 拦截所有路径（{@code /**}），在 {@code afterCompletion} 阶段清理
     * {@link SessionHolder} 的 ThreadLocal 数据。
     *
     * @param interceptor SessionHolder 清理拦截器
     * @return WebMvcConfigurer 实例
     */
    @Bean
    WebMvcConfigurer sequenceWebMvcConfigurer(SessionHolderCleanupInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
