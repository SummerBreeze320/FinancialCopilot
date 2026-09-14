package com.financial.copilot.agent.tools.configured.facade;

import com.financial.copilot.agent.tools.configured.model.ToolExecuteRequest;
import com.financial.copilot.agent.tools.configured.model.ToolExecuteResult;
import com.financial.copilot.agent.tools.configured.model.ToolSourceMode;
import com.financial.copilot.agent.tools.configured.router.ToolExecutorRouter;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 基金横向对比与对标工具集 (基于 AgentScope @Tool 注册)。
 * <p>
 * 专供 FundComparatorAgent (及 FundScreenerAgent) 注册并注入 Toolkit，用于基金横向对标与对比分析：
 * 涵盖基本资料对比、资产配置对标、前十大重仓、阶段业绩、走势回撤、相关系数矩阵、经理对比、雷达诊断、相似基金检索与 Brinson 归因。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundComparisonToolSet {

    private final ToolExecutorRouter router;

    /**
     * 基金基本资料表格横向对比。
     */
    @Tool(name = "compare_basic_info", description = "横向表格对比多只基金的基础资料(规模、净值、类型、现任经理、费率结构及申赎状态)", readOnly = true)
    public String compareBasicInfo(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表，例如 ['005827', '163402']") List<String> fundCodes) {
        return executeMulti("compare_basic_info", fundCodes, Map.of());
    }

    /**
     * 基金持有人结构横向对比。
     */
    @Tool(name = "compare_holder_structure", description = "横向对比多只基金的持有人结构占比(机构投资者比例、个人投资者比例、内部员工持有比例及持有人总户数)", readOnly = true)
    public String compareHolderStructure(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes,
            @ToolParam(name = "reportDate", description = "报告期 YYYYMMDD，可选", required = false) String reportDate) {
        Map<String, Object> extra = new HashMap<>();
        if (reportDate != null) extra.put("reportDate", reportDate);
        return executeMulti("compare_holder_structure", fundCodes, extra);
    }

    /**
     * 基金资产配置与行业分布横向对比。
     */
    @Tool(name = "compare_asset_allocation", description = "横向对比多只基金的大类资产配置比例(股票/债券/现金)、细分行业权重分布及 REITs 资产配置", readOnly = true)
    public String compareAssetAllocation(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes,
            @ToolParam(name = "reportDate", description = "报告期 YYYYMMDD，可选", required = false) String reportDate) {
        Map<String, Object> extra = new HashMap<>();
        if (reportDate != null) extra.put("reportDate", reportDate);
        return executeMulti("compare_asset_allocation", fundCodes, extra);
    }

    /**
     * 基金前十大重仓资产横向对标。
     */
    @Tool(name = "compare_top_holdings", description = "横向对标多只基金的前十大重仓股票明细、细分债券券种配置结构与重仓债券清单", readOnly = true)
    public String compareTopHoldings(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes,
            @ToolParam(name = "reportDate", description = "报告期 YYYYMMDD，可选", required = false) String reportDate) {
        Map<String, Object> extra = new HashMap<>();
        if (reportDate != null) extra.put("reportDate", reportDate);
        return executeMulti("compare_top_holdings", fundCodes, extra);
    }

    /**
     * 基金阶段收益表现与周期回报对比。
     */
    @Tool(name = "compare_performance", description = "横向对比多只基金的阶段收益表现(近1月/3月/6月/1年/3年)、固定自然周期回报、分年度回报及超额 Alpha", readOnly = true)
    public String comparePerformance(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes,
            @ToolParam(name = "startDate", description = "起始日期 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "endDate", description = "截止日期 YYYY-MM-DD，可选", required = false) String endDate) {
        Map<String, Object> extra = new HashMap<>();
        if (startDate != null) extra.put("startDate", startDate);
        if (endDate != null) extra.put("endDate", endDate);
        return executeMulti("compare_performance", fundCodes, extra);
    }

    /**
     * 基金净值时序走势与动态最大回撤曲线对标。
     */
    @Tool(name = "compare_risk_drawdown", description = "横向对标多只基金的复权单位净值累计走势、动态最大回撤时序曲线及风险收益二维散点图", readOnly = true)
    public String compareRiskDrawdown(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes,
            @ToolParam(name = "startDate", description = "起始日期 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "endDate", description = "截止日期 YYYY-MM-DD，可选", required = false) String endDate) {
        Map<String, Object> extra = new HashMap<>();
        if (startDate != null) extra.put("startDate", startDate);
        if (endDate != null) extra.put("endDate", endDate);
        return executeMulti("compare_risk_drawdown", fundCodes, extra);
    }

    /**
     * 基金收益率相关系数热力矩阵对比。
     */
    @Tool(name = "compare_correlation", description = "计算多只基金之间的区间日收益率相关系数热力矩阵，用于资产配置组合重合排雷与分散度评估", readOnly = true)
    public String compareCorrelation(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes,
            @ToolParam(name = "startDate", description = "起始日期 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "endDate", description = "截止日期 YYYY-MM-DD，可选", required = false) String endDate) {
        Map<String, Object> extra = new HashMap<>();
        if (startDate != null) extra.put("startDate", startDate);
        if (endDate != null) extra.put("endDate", endDate);
        return executeMulti("compare_correlation", fundCodes, extra);
    }

    /**
     * 基金现任经理背景与管理能力横向对标。
     */
    @Tool(name = "compare_manager", description = "横向对标多只基金现任经理的从业年限、在管总规模、历任产品业绩表现及几何年化回报", readOnly = true)
    public String compareManager(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes) {
        return executeMulti("compare_manager", fundCodes, Map.of());
    }

    /**
     * 基金综合雷达诊断画像横向对比。
     */
    @Tool(name = "compare_diagnosis", description = "多维度雷达图对比多只基金的综合量化诊断评分(收益水平、抗风险能力、择时能力、选股能力与风格稳定性)", readOnly = true)
    public String compareDiagnosis(
            @ToolParam(name = "fundCodes", description = "多只基金代码列表") List<String> fundCodes) {
        return executeMulti("compare_diagnosis", fundCodes, Map.of());
    }

    /**
     * 相似基金检索与对标推荐。
     */
    @Tool(name = "compare_similar", description = "基于基金类型、持仓重合度及风格相似度模型，检索与目标基金相似度最高的同类基金池及相似分值", readOnly = true)
    public String compareSimilar(
            @ToolParam(name = "fundCode", description = "目标基金代码，例如 005827") String fundCode) {
        return executeSingle("compare_similar", fundCode, Map.of());
    }

    /**
     * 基金 Brinson 业绩归因横向对标。
     */
    @Tool(name = "compare_brinson", description = "横向对标基金相对基准的超额收益归因，输出资产配置效应、行业选择与个股选择表格明细与对比柱状图", readOnly = true)
    public String compareBrinson(
            @ToolParam(name = "fundCode", description = "基金代码，例如 005827") String fundCode,
            @ToolParam(name = "reportDate", description = "报告期 YYYYMMDD，可选", required = false) String reportDate) {
        Map<String, Object> extra = new HashMap<>();
        if (reportDate != null) extra.put("reportDate", reportDate);
        return executeSingle("compare_brinson", fundCode, extra);
    }

    private String executeSingle(String toolId, String fundCode, Map<String, Object> extra) {
        Map<String, Object> args = new HashMap<>(extra);
        args.put("windCodes", List.of(normalizeCode(fundCode)));
        return executeInternal(toolId, args);
    }

    private String executeMulti(String toolId, List<String> fundCodes, Map<String, Object> extra) {
        Map<String, Object> args = new HashMap<>(extra);
        List<String> normalized = fundCodes.stream().map(this::normalizeCode).toList();
        args.put("windCodes", normalized);
        return executeInternal(toolId, args);
    }

    private String executeInternal(String toolId, Map<String, Object> args) {
        ToolExecuteRequest request = ToolExecuteRequest.builder()
                .toolId(toolId)
                .arguments(args)
                .sourceMode(ToolSourceMode.EXTERNAL_CONFIGURED)
                .build();
        ToolExecuteResult result = router.routeAndExecute(request);
        return result.getTextForLlm();
    }

    private String normalizeCode(String code) {
        if (code == null) return "";
        code = code.trim();
        if (code.length() == 6 && !code.contains(".")) {
            if (code.startsWith("5") || code.startsWith("6")) return code + ".SH";
            return code + ".OF";
        }
        return code;
    }
}
