package com.financial.copilot.common.intent;

/**
 * <h1>金融投研平台核心意图枚举</h1>
 * <p>
 * 定义用户自然语言指令的核心业务领域范畴，以及领域外拒识标识。
 * </p>
 *
 * @author FinancialCopilot
 */
public enum IntentType {
    /** 基金条件初筛 */
    FUND_SCREENING,
    /** 单基金多维体检与业绩归因 */
    FUND_DIAGNOSIS,
    /** 两强或多标的深度对标 */
    FUND_COMPARISON,
    /** 宏观择时与大类资产配置建议 */
    ASSET_ALLOCATION,
    /** 综合深度投研研报与终审合成 */
    REPORT_SYNTHESIS,
    /** 股票个股量化与基本面穿透 */
    STOCK_RESEARCH,
    /** 平台引导、账户信息与帮助中心 */
    SYSTEM_HELP,
    /** 领域外意图 (Out-of-Distribution, 闲聊、违规或无关内容) */
    OUT_OF_DOMAIN
}
