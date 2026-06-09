
package com.ccb.jx.seq.model;

import java.util.Date;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 序列配置实体，对应数据库 {@code sequence_config} 表。
 * <p>
 * 每条记录定义了一个全局序列的完整配置信息，包括当前最大值、步长、递增幅度、
 * 取值范围、是否循环、模式（STRICT / CACHED）、起始值等。
 *
 * @author XZJ
 */
public class SequenceConfig {

    /** 序列名称（主键） */
    private String seqName;

    /** 当前已分配的最大值，默认 1L */
    private Long maxId = 1L;

    /** 号段预取步长（CACHED 模式），默认 1000 */
    private Integer step = 1000;

    /** 每次 nextVal 递增的步幅，默认 1 */
    private Integer incrementBy = 1;

    /** 序列最小值，默认 1L */
    private Long minValue = 1L;

    /** 序列最大值，默认 Long.MAX_VALUE */
    private Long maxValue = Long.MAX_VALUE;

    /** 是否循环（0-不循环，1-循环），默认 0 */
    private Integer cycle = 0;

    /** 序列模式，默认 CACHED */
    private Mode mode = Mode.CACHED;

    /** 序列起始值，默认 1L */
    private Long startWith = 1L;

    /** 描述信息 */
    private String description;

    /** 更新时间 */
    private Date updateTime;

    /** 创建时间 */
    private Date createTime;

    /** 无参构造器 */
    public SequenceConfig() {
    }

    /**
     * 使用 Builder 创建序列配置的静态工厂方法。
     *
     * @param seqName 序列名称（必填）
     * @return Builder 实例
     */
    public static Builder builder(String seqName) {
        return new Builder(seqName);
    }

    /**
     * 序列配置 Builder。
     * <p>
     * 为业务代码提供类型安全的配置创建方式，所有字段均有默认值。
     * 保留无参构造器 + setter 供 MyBatis 映射使用。
     * </p>
     */
    public static class Builder {

        private final String seqName;
        private Long maxId = 1L;
        private Integer step = 1000;
        private Integer incrementBy = 1;
        private Long minValue = 1L;
        private Long maxValue = Long.MAX_VALUE;
        private Integer cycle = 0;
        private Mode mode = Mode.CACHED;
        private Long startWith = 1L;
        private String description;

        Builder(String seqName) {
            this.seqName = seqName;
        }

        public Builder maxId(Long maxId) {
            this.maxId = maxId;
            return this;
        }

        public Builder step(Integer step) {
            this.step = step;
            return this;
        }

        public Builder incrementBy(Integer incrementBy) {
            this.incrementBy = incrementBy;
            return this;
        }

        public Builder minValue(Long minValue) {
            this.minValue = minValue;
            return this;
        }

        public Builder maxValue(Long maxValue) {
            this.maxValue = maxValue;
            return this;
        }

        public Builder cycle(Integer cycle) {
            this.cycle = cycle;
            return this;
        }

        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public Builder startWith(Long startWith) {
            this.startWith = startWith;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /**
         * 构建序列配置对象。
         *
         * @return 不可变的序列配置
         */
        public SequenceConfig build() {
            SequenceConfig config = new SequenceConfig();
            config.setSeqName(seqName);
            config.setMaxId(maxId);
            config.setStep(step);
            config.setIncrementBy(incrementBy);
            config.setMinValue(minValue);
            config.setMaxValue(maxValue);
            config.setCycle(cycle);
            config.setMode(mode);
            config.setStartWith(startWith);
            config.setDescription(description);
            return config;
        }
    }

    // ====== getter / setter ======

    public String getSeqName() {
        return seqName;
    }

    public void setSeqName(String seqName) {
        this.seqName = seqName;
    }

    // null-safe: 防止 MyBatis 映射 NULL 导致自动拆箱 NPE
    public Long getMaxId() {
        if (maxId == null) {
            return 1L;
        }
        return maxId;
    }

    public void setMaxId(Long maxId) {
        this.maxId = maxId;
    }

    // null-safe: 防止 MyBatis 映射 NULL 导致自动拆箱 NPE
    public Integer getStep() {
        if (step == null) {
            return 1000;
        }
        return step;
    }

    public void setStep(Integer step) {
        this.step = step;
    }

    // null-safe: 防止 MyBatis 映射 NULL 导致自动拆箱 NPE
    public Integer getIncrementBy() {
        if (incrementBy == null) {
            return 1;
        }
        return incrementBy;
    }

    public void setIncrementBy(Integer incrementBy) {
        this.incrementBy = incrementBy;
    }

    // null-safe: 防止 MyBatis 映射 NULL 导致自动拆箱 NPE
    public Long getMinValue() {
        if (minValue == null) {
            return 1L;
        }
        return minValue;
    }

    public void setMinValue(Long minValue) {
        this.minValue = minValue;
    }

    // null-safe: 防止 MyBatis 映射 NULL 导致自动拆箱 NPE
    public Long getMaxValue() {
        if (maxValue == null) {
            return Long.MAX_VALUE;
        }
        return maxValue;
    }

    public void setMaxValue(Long maxValue) {
        this.maxValue = maxValue;
    }

    // null-safe: 防止 MyBatis 映射 NULL 导致自动拆箱 NPE
    public Integer getCycle() {
        if (cycle == null) {
            return 0;
        }
        return cycle;
    }

    public void setCycle(Integer cycle) {
        this.cycle = cycle;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    // null-safe: 防止 MyBatis 映射 NULL 导致自动拆箱 NPE
    public Long getStartWith() {
        if (startWith == null) {
            return 1L;
        }
        return startWith;
    }

    public void setStartWith(Long startWith) {
        this.startWith = startWith;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Date getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Date updateTime) {
        this.updateTime = updateTime;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SequenceConfig)) {
            return false;
        }
        SequenceConfig that = (SequenceConfig) o;
        return Objects.equals(seqName, that.seqName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(seqName);
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", SequenceConfig.class.getSimpleName() + "[", "]")
                .add("seqName='" + seqName + "'")
                .add("maxId=" + maxId)
                .add("step=" + step)
                .add("incrementBy=" + incrementBy)
                .add("minValue=" + minValue)
                .add("maxValue=" + maxValue)
                .add("cycle=" + cycle)
                .add("mode=" + mode)
                .add("startWith=" + startWith)
                .add("description='" + description + "'")
                .add("updateTime=" + updateTime)
                .add("createTime=" + createTime)
                .toString();
    }
}
