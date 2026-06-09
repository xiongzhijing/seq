
package com.ccb.jx.seq.model;

/**
 * 原生 Sequence 方言枚举。
 * <p>
 * 控制 STRICT 模式的值生成策略：
 * <ul>
 *   <li>{@link #NONE} — 使用 {@code SELECT ... FOR UPDATE}（默认）</li>
 *   <li>{@link #TDSQL} — 使用 TDSQL 原生 {@code tdsql_nextval()}</li>
 * </ul>
 * </p>
 *
 * @author XZJ
 */
public enum SequenceDialect {

    /**
     * 不使用原生 Sequence，走 SELECT ... FOR UPDATE。
     */
    NONE,

    /**
     * 使用 TDSQL 原生 Sequence（tdsql_nextval / CREATE TDSQL_SEQUENCE）。
     */
    TDSQL
}
