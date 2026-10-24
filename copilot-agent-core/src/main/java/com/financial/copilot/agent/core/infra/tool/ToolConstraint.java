package com.financial.copilot.agent.core.infra.tool;

import java.lang.annotation.*;

/**
 * <h1>工具参数同源约束注解 (ToolConstraint)</h1>
 * <p>
 * 为 Agent 工具参数提供刚性校验元数据，确保 Java DTO 强校验规则与大模型看到的分发 Schema 严格同源一致。
 * </p>
 *
 * @author FinancialCopilot
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolConstraint {
    /** 参数名称（用于在无 -parameters 编译参数下精准映射） */
    String name() default "";

    /** 允许的枚举常量值数组 */
    String[] allowedValues() default {};

    /** 允许的数值下限 */
    double minValue() default Double.NEGATIVE_INFINITY;

    /** 允许的数值上限 */
    double maxValue() default Double.POSITIVE_INFINITY;

    /** 正则格式匹配表达式 */
    String regexPattern() default "";

    /** 是否必填 */
    boolean required() default true;
}
