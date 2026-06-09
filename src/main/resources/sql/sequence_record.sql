CREATE TABLE IF NOT EXISTS sequence_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    seq_name VARCHAR(64) NOT NULL COMMENT '序列名称',
    start_value BIGINT NOT NULL COMMENT '号段起始值',
    end_value BIGINT NOT NULL COMMENT '号段结束值',
    instance_id VARCHAR(64) DEFAULT NULL COMMENT '应用实例ID',
    status VARCHAR(16) NOT NULL DEFAULT 'ALLOCATED' COMMENT '状态: ALLOCATED-已分配, EXPIRED-已过期, RECYCLED-已回收',
    alloc_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '分配时间',
    expire_time DATETIME DEFAULT NULL COMMENT '过期时间',
    INDEX idx_seq_name (seq_name),
    INDEX idx_instance_id (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='序列号段分配记录';
