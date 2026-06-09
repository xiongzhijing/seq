# mysql-sequence-spring-boot-starter

[![Java](https://img.shields.io/badge/Java-8+-orange.svg)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.x-brightgreen.svg)]()
[![Version](https://img.shields.io/badge/version-2.0.0-blue.svg)]()

Spring Boot Starter，在 MySQL 上提供 Oracle 兼容的 `Sequence` 功能。以 Bean 形式嵌入业务应用 Spring 上下文，弥补 MySQL `AUTO_INCREMENT` 无法满足的序列号需求。

## 特性

- **Oracle 兼容** — 支持 `NEXTVAL` / `CURRVAL` 语义、`START WITH`、`INCREMENT BY`、`CYCLE`
- **双模式** — STRICT（严格连续，悲观锁）与 CACHED（号段缓存，高吞吐）
- **TDSQL 原生序列** — 可选 `NativeStrictSequence`，委托 `tdsql_nextval()` 消除应用层行锁
- **双 Buffer 预加载** — 零等待号段切换，CACHED 模式单机 TPS ≥ 5000
- **动态步长** — 基于消耗速率自动调整号段大小，阻尼机制防止抖动
- **会话隔离** — `CURRVAL` 基于 ThreadLocal，HTTP 请求生命周期自动清理
- **优雅关闭** — 双入口保障（`ContextClosedEvent` + `@PreDestroy`），未消耗号段审计日志
- **独立数据源** — 序列操作与业务库连接池隔离，零侵入

## 快速开始

### 1. 建表

在目标 MySQL/TDSQL 数据库中执行 DDL：

```sql
-- 序列配置表
CREATE TABLE IF NOT EXISTS sequence_config (
    seq_name VARCHAR(64) NOT NULL PRIMARY KEY COMMENT '序列名称',
    max_id BIGINT NOT NULL DEFAULT 1 COMMENT '当前已分配的最大号段终点',
    step INT NOT NULL DEFAULT 1000 COMMENT '号段步长',
    increment_by INT NOT NULL DEFAULT 1 COMMENT '序列步长',
    min_value BIGINT NOT NULL DEFAULT 1 COMMENT '最小值',
    max_value BIGINT NOT NULL DEFAULT 9223372036854775807 COMMENT '最大值',
    cycle TINYINT NOT NULL DEFAULT 0 COMMENT '是否循环: 0-否, 1-是',
    mode VARCHAR(16) NOT NULL DEFAULT 'CACHED' COMMENT '序列模式: STRICT/CACHED',
    start_with BIGINT NOT NULL DEFAULT 1 COMMENT '起始值',
    description VARCHAR(255) DEFAULT NULL COMMENT '描述',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 序列号段分配记录
CREATE TABLE IF NOT EXISTS sequence_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    seq_name VARCHAR(64) NOT NULL,
    start_value BIGINT NOT NULL,
    end_value BIGINT NOT NULL,
    instance_id VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ALLOCATED',
    alloc_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_time DATETIME DEFAULT NULL,
    INDEX idx_seq_name (seq_name),
    INDEX idx_instance_id (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 2. 引入依赖

```xml
<dependency>
    <groupId>com.ccb.jx</groupId>
    <artifactId>mysql-sequence-spring-boot-starter</artifactId>
    <version>2.0.0</version>
</dependency>
```

### 3. 配置数据源

在 `application.yml` 中配置序列独立数据源：

```yaml
sequence:
  datasource:
    url: jdbc:mysql://localhost:3306/seq_db?useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: your_password
```

### 4. 使用

```java
@Service
public class OrderService {

    private final SequenceService sequenceService;

    public OrderService(SequenceService sequenceService) {
        this.sequenceService = sequenceService;
    }

    public void createOrder() {
        // 生成下一个序列值
        long orderId = sequenceService.nextVal("order_seq");

        // 获取当前会话中该序列的最近一次 nextVal（Oracle CURRVAL 语义）
        long currentId = sequenceService.currVal("order_seq");
    }
}
```

首次调用 `nextVal` 时，若 `sequence_config` 表中不存在该序列，将自动以默认配置创建：

| 字段 | 默认值 | 说明 |
|---|---|---|
| mode | STRICT | 严格连续模式 |
| increment_by | 1 | 每次递增步长，决定生成序列如 1,2,3,... |
| start_with | 1 | 首次 nextVal 应返回的值 |
| step | 1000 | 仅 CACHED 模式有效（号段预取大小），STRICT 模式下不参与计算 |

> **注意**：默认配置下首次 `nextVal` 返回 **2** 而非 1。因为 `max_id` 默认值为 1（表示"已分配的最大值"），`nextVal = max_id + increment_by = 1 + 1 = 2`。若需从 1 开始，请手动 INSERT 配置行并设置 `max_id=0`。

## 配置项

### 核心配置

所有配置项前缀为 `sequence`：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `sequence.datasource.url` | - | 序列独立数据源 JDBC URL（与 `jdbc-url` 至少配一个） |
| `sequence.datasource.jdbc-url` | - | 同上，兼容 Spring Boot 2.x 属性名 |
| `sequence.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` | JDBC 驱动类名 |
| `sequence.datasource.username` | - | 数据库用户名 |
| `sequence.datasource.password` | - | 数据库密码 |
| `sequence.instance-id` | 自动生成 `ip:port` | 实例标识，用于号段审计记录 |
| `sequence.config-cache-ttl-minutes` | 5 | 配置缓存刷新间隔（分钟），控制 DB 配置变更的感知延迟 |
| `sequence.native-sequence-dialect` | NONE | 原生序列方言：NONE（应用层 FOR UPDATE）/ TDSQL（DB 原生序列） |
| `sequence.controller-enabled` | false | 是否启用监控 REST API（`/sequence/*`） |

### Fallback 机制

若未配置 `sequence.datasource.url`，自动 Fallback 到业务应用的 `spring.datasource`，复用业务连接池。此时需确保业务 DataSource 已配置。

### HikariCP 连接池

独立数据源默认参数已针对序列场景优化，无需额外调整：

| 参数 | 默认值 | 说明 |
|---|---|---|
| maximumPoolSize | 5 | 序列操作并发低，小池足够 |
| minimumIdle | 1 | 最小空闲连接 |
| connectionTimeout | 3000ms | 获取连接超时时间 |
| maxLifetime | 1800000ms (30min) | 连接最大生命周期 |

### 完整配置示例

```yaml
# MyBatis 映射配置（starter 内置 mapper XML 需指定 classpath）
mybatis:
  mapper-locations: classpath*:com/ccb/jx/seq/repository/*.xml
  configuration:
    map-underscore-to-camel-case: true  # 驼峰自动映射

sequence:
  # 独立数据源（必填，或 Fallback 到 spring.datasource）
  datasource:
    url: jdbc:mysql://localhost:3306/sequence?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: root
    password: your_password
    # jdbc-url: jdbc:mysql://...  # Spring Boot 2.x 兼容写法，与 url 二选一

  # 实例标识（可选，默认自动生成 ip:port 格式，用于审计记录区分多实例）
  # instance-id: 10.0.1.5:8080

  # 配置缓存 TTL（可选，默认 5 分钟，DBA 修改 sequence_config 后最长 TTL 内生效）
  # config-cache-ttl-minutes: 5

  # 原生序列方言（可选，TDSQL 环境可启用消除应用层行锁）
  # native-sequence-dialect: TDSQL

  # 监控 API（可选，默认禁用，生产环境按需开启）
  controller-enabled: true
```

### 连接池共享模式（Fallback）

不想维护独立数据库？直接复用业务数据源：

```yaml
# 不配置 sequence.datasource.*，自动 Fallback 到 spring.datasource
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/myapp
    username: root
    password: your_password
```

此时序列操作与业务共享连接池，适用于单机部署或序列 TPS 较低的场景。

## 两种模式

### STRICT — 严格连续

每次 `nextVal` 通过 `SELECT ... FOR UPDATE` 悲观锁串行化分配，保证值严格连续不跳号。

- 单机 TPS ~50，4 实例 ~100-200
- 适用于：订单号、凭证号等不允许跳号的场景
- 事务隔离：`PROPAGATION_REQUIRES_NEW`，独立于业务事务提交

### CACHED — 号段缓存

批量预取号段到内存，通过双 Buffer 机制实现零等待切换。

- 单机 TPS ≥ 5000，TP999 ≤ 1ms
- 适用于：高并发、可容忍少量号段空洞的场景
- 动态步长：根据消耗速率自动调整号段大小（阻尼 2x 上限，全局范围 [100, 1M]）
- 崩溃时未消耗号段产生空洞（Oracle 兼容行为）

### 模式选择

在 `sequence_config` 表中通过 `mode` 字段指定：

```sql
-- 严格连续模式
INSERT INTO sequence_config (seq_name, mode, start_with, increment_by, max_value, cycle)
VALUES ('order_seq', 'STRICT', 1, 1, 999999999, 0);

-- 号段缓存模式
INSERT INTO sequence_config (seq_name, mode, step, start_with, max_value, cycle)
VALUES ('user_id_seq', 'CACHED', 1000, 1, 999999999, 0);
```

## TDSQL 原生序列

当数据库为 TDSQL 且支持原生 Sequence 时，可启用原生序列模式消除应用层行锁：

```yaml
sequence:
  native-sequence-dialect: TDSQL
  datasource:
    url: jdbc:mysql://localhost:3306/seq_db
    username: root
    password: your_password
```

启用后，STRICT 模式的序列将委托 `tdsql_nextval()` 生成值，启动时自动同步 `sequence_config` 中所有 STRICT 序列到 DB Sequence 对象。

## CURRVAL 会话隔离

`currVal(seqName)` 返回当前会话（ThreadLocal）中该序列最近一次 `nextVal` 的值，与 Oracle 行为一致：

- 首次 `currVal` 前未调用 `nextVal` → 抛出 `CURRVAL_NOT_INITIALIZED`
- HTTP 请求结束后自动清理 ThreadLocal（`SessionHolderCleanupInterceptor`）
- 非 HTTP 场景需手动清理，推荐使用 `SessionHolder.wrap()` 自动清理：

```java
// 定时任务
@Scheduled(fixedRate = 5000)
public void scheduledTask() {
    SessionHolder.wrap(() -> {
        long id = sequenceService.nextVal("task_seq");
        long current = sequenceService.currVal("task_seq"); // 同一会话内有效
        // ... 业务逻辑
    }).run();
}

// 消息消费者
@KafkaListener(topics = "order")
public void onMessage(ConsumerRecord<String, String> record) {
    SessionHolder.wrap(() -> {
        long id = sequenceService.nextVal("msg_seq");
        // ... 处理消息
    }).run();
}

// 异步线程池
executorService.submit(SessionHolder.wrap(() -> {
    long id = sequenceService.nextVal("async_seq");
    return id;
}));
```

## 监控 API

默认禁用，需显式开启：

```yaml
sequence:
  controller-enabled: true
```

| 端点 | 说明 |
|---|---|
| `GET /sequence/{seqName}` | 查询单个序列状态：seqName, mode, currentValue, remaining, totalCapacity |
| `GET /sequence/list` | 列出所有序列摘要：seqName, mode, maxId, step, description |

## 优雅关闭

Spring 上下文关闭时自动执行：

1. 设置 `SHUTTING_DOWN` 状态 → 新请求快速失败
2. 关闭异步线程池（5s 等待 → 强制终止）
3. 记录未消耗号段范围到审计日志
4. 设置 `SHUTDOWN` 状态

双入口保障（`ContextClosedEvent` + `@PreDestroy`），`AtomicBoolean` 保证只执行一次。

## 错误码

| 错误码 | 标识 | 说明 |
|---|---|---|
| SEQ_001 | SEQ_NOT_FOUND | 序列不存在 |
| SEQ_002 | SEQ_EXHAUSTED | 序列已耗尽 |
| SEQ_003 | CURRVAL_NOT_INITIALIZED | currVal 尚未在当前会话中初始化 |
| SEQ_004 | SHUTTING_DOWN | 序列服务正在关闭 |
| SEQ_005 | DB_ERROR | 数据库操作失败 |
| SEQ_006 | INVALID_CONFIG | 序列配置无效 |
| SEQ_007 | SEGMENT_LOAD_FAILED | 号段加载失败 |
| SEQ_008 | NATIVE_SEQ_ERROR | 原生序列操作失败 |

## 项目结构

```
src/main/java/com/ccb/jx/seq/
├── config/        # 自动配置、属性、关闭钩子
├── core/          # SequenceService, StrictSequence, CachedSequence, NativeStrictSequence
├── buffer/        # DoubleBuffer, Segment, EmptySegment
├── model/         # 实体类与枚举
├── repository/    # MyBatis Mapper
├── dialect/       # 原生序列方言（TDSQL）
├── exception/     # 异常与错误码
├── controller/    # 监控 REST API
└── session/       # CURRVAL 会话隔离
```

## 构建

```bash
mvn clean install -DskipTests
```

## 测试

```bash
mvn test
```

## 技术栈

- Java 8+ / Spring Boot 2.7.x / MyBatis
- HikariCP（连接池） / TDSQL（MySQL 5.7+）
- 无外部依赖：不依赖 Redis、Caffeine 等中间件
