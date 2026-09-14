package com.financial.copilot.data.fund.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.financial.copilot.data.fund.po.FundReportVectorPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <h1>基金定性报告向量数据访问映射器 (MyBatis-Plus + PGVector Mapper)</h1>
 * <p>
 * 提供针对 {@code fund_report_vector} 表的高维文本向量插入及基于余弦距离 (&lt;=&gt;) 的近似最近邻 (ANN) 检索。
 * </p>
 *
 * @author FinancialCopilot
 */
@Mapper
public interface FundReportVectorMapper extends BaseMapper<FundReportVectorPO> {

    /**
     * 基于 PostgreSQL PGVector 的余弦距离 (<=>) 进行近邻向量检索，获取与目标向量最相似的定性季报段落
     */
    @Select("SELECT id, fund_code, manager_name, report_quarter, section_title, content, created_at " +
            "FROM fund_report_vector WHERE fund_code = #{fundCode} " +
            "ORDER BY embedding <=> CAST(#{embeddingStr} AS vector) LIMIT #{topK}")
    List<FundReportVectorPO> searchSimilarReportSections(
            @Param("fundCode") String fundCode,
            @Param("embeddingStr") String embeddingStr,
            @Param("topK") int topK);
}
