package com.financial.copilot.data.rag.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.financial.copilot.data.config.StringListTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <h1>公募基金指标元数据持久化对象 (PO)</h1>
 * <p>
 * 对应 PostgreSQL 数据库表 <code>rag_fund_metric</code>。
 * 存储 169 项公募基金量化与基础指标的元数据定义、使用场景及 1024 维 HNSW 余弦向量。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "rag_fund_metric", autoResultMap = true)
public class RagFundMetricPO {

    /**
     * 指标唯一助记符代码（主键，如 f_return_1y, f_risk_sharpe）
     */
    @TableId(value = "mnemonic", type = IdType.INPUT)
    private String mnemonic;

    /**
     * 指标标准中文名称（如 近1年回报、夏普比率、最大回撤）
     */
    @TableField("index_name")
    private String indexName;

    /**
     * 一级业务大类名称（如 收益指标、风险指标、规模指标、持仓指标）
     */
    @TableField("parent_name")
    private String parentName;

    /**
     * 指标详细中文描述与投资含义
     */
    @TableField("description")
    private String description;

    /**
     * 拼接后的 Embedding 特征原始文本（用于本地 1024 维向量模型推理）
     */
    @TableField("embedding_text")
    private String embeddingText;

    /**
     * 1024 维高维向量字符串（pgvector 格式：[0.0123, -0.0456, ...]）
     */
    @TableField("embedding")
    private String embedding;

    /**
     * 来源指标主键 ID
     */
    @TableField("source_indicator_id")
    private Long sourceIndicatorId;

    /**
     * 支持的下游场景类型列表（如 filter、sort、group）
     */
    @TableField(value = "supported_usage", typeHandler = StringListTypeHandler.class)
    private List<String> supportedUsage;

    /**
     * 适用基金产品范围说明（如 股票型基金、混合型基金）
     */
    @TableField("applicable_products")
    private String applicableProducts;

    /**
     * 指标中文别名及同义词列表（用于词面精确及前缀匹配）
     */
    @TableField(value = "aliases", typeHandler = StringListTypeHandler.class)
    private List<String> aliases;

    /**
     * 记录版本号
     */
    @TableField("version")
    private Integer version;

    /**
     * 是否启用状态
     */
    @TableField("enabled")
    private Boolean enabled;

    /**
     * 创建与入库时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
