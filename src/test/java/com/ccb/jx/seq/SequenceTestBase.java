
package com.ccb.jx.seq;

import com.ccb.jx.seq.config.SequenceProperties;
import com.ccb.jx.seq.core.CachedSequence;
import com.ccb.jx.seq.core.SequenceService;
import com.ccb.jx.seq.core.SequenceStrategy;
import com.ccb.jx.seq.core.StrictSequence;
import com.ccb.jx.seq.model.Mode;
import com.ccb.jx.seq.model.SequenceConfig;
import com.ccb.jx.seq.repository.SequenceRepository;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 集成测试基类。
 * <p>
 * 提供共享的 Spring 测试配置，包含：
 * <ul>
 *   <li>嵌入式 H2 数据源（MySQL 兼容模式）</li>
 *   <li>MyBatis SqlSessionFactory（含 Mapper XML 映射）</li>
 *   <li>{@link SequenceRepository} Mapper Bean</li>
 *   <li>{@link StrictSequence}、{@link CachedSequence}、{@link SequenceService} 核心服务 Bean</li>
 * </ul>
 * </p>
 *
 * <p>子类继承此类后通过 {@code @Autowired} 注入所需 Bean 即可编写集成测试。</p>
 *
 * @author XZJ
 */
@SpringJUnitConfig(classes = SequenceTestBase.TestConfiguration.class)
public abstract class SequenceTestBase {

    /**
     * 共享的测试配置。
     * <p>
     * 手动装配所有 Bean，避免 {@code @SpringBootTest} 自动配置与
     * {@link com.ccb.jx.seq.config.SequenceAutoConfiguration}
     * 之间的冲突。控制力强，适合库项目的集成测试。
     * </p>
     */
    @Configuration
    public static class TestConfiguration {

        // ==================== 数据源 ====================

        /**
         * 嵌入式 H2 数据源。
         * <p>
         * 使用 MySQL 兼容模式（{@code MODE=MySQL}），确保 H2 支持
         * {@code FOR UPDATE}、{@code ON DUPLICATE KEY UPDATE} 等 MySQL 语法。
         * {@code DB_CLOSE_DELAY=-1} 保证连接关闭后数据不丢失。
         * </p>
         */
        @Bean
        public DataSource dataSource() {
            DataSource ds = DataSourceBuilder.create()
                    .url("jdbc:h2:mem:seqtest;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
                    .driverClassName("org.h2.Driver")
                    .username("sa")
                    .password("")
                    .build();
            // 配置 HikariCP 连接池支持 100 并发线程
            // H2 内存数据库单连接并发有限，pool=50 提供足够排队缓冲
            if (ds instanceof com.zaxxer.hikari.HikariDataSource) {
                com.zaxxer.hikari.HikariDataSource hds = (com.zaxxer.hikari.HikariDataSource) ds;
                hds.setMaximumPoolSize(50);
                hds.setMinimumIdle(5);
                hds.setConnectionTimeout(60000);
                hds.setIdleTimeout(300000);
            }
            return ds;
        }

        /**
         * 事务管理器。
         * <p>
         * 支持 {@link StrictSequence#nextVal(String)} 和
         * {@link CachedSequence#loadSegment(String)} 中的
         * {@code REQUIRES_NEW} 传播行为。
         * </p>
         */
        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        // ==================== MyBatis ====================

        /**
         * MyBatis SqlSessionFactory。
         * <p>
         * 设置 Mapper XML 映射文件路径和驼峰命名自动映射。
         * 与生产环境 {@link com.ccb.jx.config.SequenceAutoConfiguration}
         * 中的配置保持一致。
         * </p>
         */
        @Bean
        public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            factoryBean.setTypeAliasesPackage("com.ccb.jx.seq.model");

            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            factoryBean.setMapperLocations(
                    resolver.getResources("classpath*:com/ccb/jx/seq/repository/*.xml")
            );

            org.apache.ibatis.session.Configuration config =
                    new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            factoryBean.setConfiguration(config);

            return factoryBean.getObject();
        }

        /**
         * {@link SequenceRepository} Mapper Bean。
         * <p>
         * 通过 {@link MapperFactoryBean} 手动注册，避免 {@code @MapperScan}
         * 或 {@code MapperScannerConfigurer} 对测试上下文的侵入。
         * </p>
         */
        @Bean
        public MapperFactoryBean<SequenceRepository> sequenceRepository(
                SqlSessionFactory sqlSessionFactory) {
            MapperFactoryBean<SequenceRepository> factoryBean =
                    new MapperFactoryBean<>(SequenceRepository.class);
            factoryBean.setSqlSessionFactory(sqlSessionFactory);
            return factoryBean;
        }

        // ==================== 序列服务核心 Bean ====================

        /**
         * 严格连续模式序列生成器。
         */
        @Bean
        public StrictSequence strictSequence(SequenceRepository sequenceRepository,
                                              PlatformTransactionManager transactionManager) {
            return new StrictSequence(sequenceRepository, transactionManager, 30);
        }

        /**
         * 序列服务配置属性。
         */
        @Bean
        public SequenceProperties sequenceProperties() {
            return new SequenceProperties();
        }

        /**
         * 号段缓存模式序列生成器。
         */
        @Bean
        public CachedSequence cachedSequence(SequenceRepository sequenceRepository,
                                              PlatformTransactionManager transactionManager,
                                              SequenceProperties properties) {
            return new CachedSequence(
                    sequenceRepository,
                    transactionManager,
                    30,
                    properties
            );
        }

        /**
         * 统一序列服务入口。
         */
        @Bean
        public SequenceService sequenceService(
                StrictSequence strictSequence,
                CachedSequence cachedSequence,
                SequenceRepository sequenceRepository) {
            Map<Mode, SequenceStrategy> strategyMap = new HashMap<>();
            strategyMap.put(Mode.STRICT, strictSequence);
            strategyMap.put(Mode.CACHED, cachedSequence);
            return new SequenceService(strategyMap, sequenceRepository);
        }
    }
}
