package com.financial.copilot.common.tool;

import java.lang.annotation.*;

/**
 * <h1>高危工具声明注解 (HighRiskTool)</h1>
 * <p>标注在敏感操作工具方法上，触发动作侧护栏决策为 ASK，进入 Human-in-the-loop 人工审批挂起流程。</p>
 *
 * @author FinancialCopilot
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface HighRiskTool {
    /** 风险分类，如 ASSET_TRANSACTION, ACCOUNT_MODIFICATION, SYSTEM_CONFIG */
    String category() default "GENERAL";

    /** 风险等级，如 HIGH, CRITICAL */
    String riskLevel() default "HIGH";

    /** 是否强制要求人工审批 */
    boolean requiresApproval() default true;
}
