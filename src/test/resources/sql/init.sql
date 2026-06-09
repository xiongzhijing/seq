-- ========================================================================
-- H2 兼容的 DDL 初始化脚本 (MySQL 兼容模式)
-- H2 注意事项：
--   1. TINYINT → INT
--   2. ON UPDATE CURRENT_TIMESTAMP → 由应用逻辑处理
--   3. ENGINE/CHARSET 语法被忽略 (H2 不识别但 MySQL 模式可容忍)
-- ========================================================================

SET MODE MySQL;

-- ========================================================================
-- Step 1: 建表（CREATE TABLE 必须在任何 DML 之前）
-- ========================================================================

-- 序列配置表
CREATE TABLE IF NOT EXISTS sequence_config (
    seq_name VARCHAR(64) NOT NULL PRIMARY KEY,
    max_id BIGINT NOT NULL DEFAULT 1,
    step INT NOT NULL DEFAULT 1000,
    increment_by INT NOT NULL DEFAULT 1,
    min_value BIGINT NOT NULL DEFAULT 1,
    max_value BIGINT NOT NULL DEFAULT 9223372036854775807,
    cycle INT NOT NULL DEFAULT 0,
    mode VARCHAR(16) NOT NULL DEFAULT 'CACHED',
    start_with BIGINT NOT NULL DEFAULT 1,
    description VARCHAR(255),
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 号段分配记录表
CREATE TABLE IF NOT EXISTS sequence_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    seq_name VARCHAR(64) NOT NULL,
    start_value BIGINT NOT NULL,
    end_value BIGINT NOT NULL,
    instance_id VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'ALLOCATED',
    alloc_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_time TIMESTAMP
);

-- ========================================================================
-- Step 2: 清理已有数据（幂等性保证，表已存在不会报错）
-- ========================================================================

DELETE FROM sequence_record;
DELETE FROM sequence_config;

-- ========================================================================
-- Step 3: 插入测试数据
-- ========================================================================

-- STRICT 模式序列：严格连续，max_value=10000，用于测试耗尽场景
INSERT INTO sequence_config (seq_name, max_id, step, increment_by, min_value, max_value, cycle, mode, start_with, description)
VALUES ('TEST_STRICT', 1, 1000, 1, 1, 10000, 0, 'STRICT', 1, 'Test strict sequence');

-- STRICT 模式序列（自定义步长 increment_by=2）
INSERT INTO sequence_config (seq_name, max_id, step, increment_by, min_value, max_value, cycle, mode, start_with, description)
VALUES ('TEST_STRICT_STEP2', 1, 1000, 2, 1, 10000, 0, 'STRICT', 1, 'Test strict sequence with increment by 2');

-- CACHED 模式序列
INSERT INTO sequence_config (seq_name, max_id, step, increment_by, min_value, max_value, cycle, mode, start_with, description)
VALUES ('TEST_CACHED', 1, 1000, 1, 1, 999999999, 0, 'CACHED', 1, 'Test cached sequence');

-- CACHED 模式序列（小步长，便于触发段切换）
INSERT INTO sequence_config (seq_name, max_id, step, increment_by, min_value, max_value, cycle, mode, start_with, description)
VALUES ('TEST_CACHED_SMALL', 1, 10, 1, 1, 999999999, 0, 'CACHED', 1, 'Test cached sequence with small step');

-- CYCLE 模式序列：达到 max_value=10 后回绕到 min_value=1
INSERT INTO sequence_config (seq_name, max_id, step, increment_by, min_value, max_value, cycle, mode, start_with, description)
VALUES ('TEST_CYCLE', 1, 1000, 1, 1, 10, 1, 'STRICT', 1, 'Test cycle sequence');
