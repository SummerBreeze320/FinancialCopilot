package com.financial.copilot.agent.tools.fund;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.financial.copilot.data.fund.mapper.FundReportVectorMapper;
import com.financial.copilot.data.fund.po.FundReportVectorPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * <h1>基金定期报告定性策略观点召回工具 (MyBatis-Plus + PGVector 混合 RAG)</h1>
 * <p>
 * 从高维向量库和关系表中检索基金经理在各期季报中对宏观经济、行业轮动与投资哲学的定性表态，
 * 供 ComparatorAgent 评估其知行合一性与风格稳定性。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class FundReportRetrieverTool {

    private final FundReportVectorMapper reportVectorMapper;

    public FundReportRetrieverTool(FundReportVectorMapper reportVectorMapper) {
        this.reportVectorMapper = reportVectorMapper;
    }

    /**
     * 获取指定基金最新的季度策略观点全文切片
     *
     * @param fundCode 6位基金代码
     * @return 格式化后的季报观点文本
     */
    public String getLatestQuarterlyReportView(String fundCode) {
        log.info("[TOOL CALL-FUND] 查询基金定性季报策略观点: fundCode={}", fundCode);
        try {
            List<FundReportVectorPO> list = reportVectorMapper.selectList(
                    new LambdaQueryWrapper<FundReportVectorPO>()
                            .eq(FundReportVectorPO::getFundCode, fundCode)
                            .orderByDesc(FundReportVectorPO::getReportQuarter)
            );

            if (list == null || list.isEmpty()) {
                return "暂无该基金已录入的季报定性投资策略观点。";
            }

            StringBuilder sb = new StringBuilder();
            for (FundReportVectorPO po : list) {
                sb.append("【").append(po.getReportQuarter()).append(" 季报策略观点 - ")
                        .append(po.getManagerName()).append("】:\n")
                        .append(po.getContent()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("查询季报策略观点异常: fundCode={}", fundCode, e);
            return "检索季报定性内容失败: " + e.getMessage();
        }
    }

    /**
     * 基于余弦距离 (<=>) 进行近邻向量检索，召回语义最相近的研报切片
     *
     * @param fundCode     6位基金代码
     * @param embeddingStr 1536维文本向量 JSON 字符串
     * @param topK         召回最大段落数
     * @return 相似研报切片 PO 列表
     */
    public List<FundReportVectorPO> searchSimilarSections(String fundCode, String embeddingStr, int topK) {
        try {
            return reportVectorMapper.searchSimilarReportSections(fundCode, embeddingStr, topK);
        } catch (Exception e) {
            log.error("向量语义检索季报失败: fundCode={}", fundCode, e);
            return List.of();
        }
    }
}
