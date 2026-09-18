package com.financial.copilot.domain.shared.rag.port;

import com.financial.copilot.domain.shared.rag.entity.RagFundMetric;
import com.financial.copilot.domain.shared.rag.entity.RagFundSector;

import java.util.List;

/**
 * <h1>Neo4j 图谱拓扑检索与推理端口契约</h1>
 */
public interface RagGraphPort {

    /**
     * 批量同步板块分类树节点与 PARENT_OF 关系
     */
    void syncSectorGraph(List<RagFundSector> sectors);

    /**
     * 批量同步指标分类与指标节点关系
     */
    void syncMetricGraph(List<RagFundMetric> metrics);

    /**
     * 拓扑下钻：展开指定板块节点之下的所有叶子节点 ID
     */
    List<String> expandLeafSectors(String sectorId);

    /**
     * 同分类指标关联发现：查询同一分类下的其它推荐指标
     */
    List<String> findMetricSiblings(String mnemonic, int limit);

    /**
     * 统计 Neo4j 中板块节点数
     */
    long countSectorNodes();

    /**
     * 统计 Neo4j 中指标节点数
     */
    long countMetricNodes();
}
