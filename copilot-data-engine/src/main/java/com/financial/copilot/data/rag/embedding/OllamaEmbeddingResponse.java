package com.financial.copilot.data.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <h1>本地 Ollama Embedding API 响应体 (DTO)</h1>
 * <p>
 * 接收 <code>POST /api/embed</code> 返回的 1024 维高维浮点数向量数组及推理性能指标。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaEmbeddingResponse {

    /**
     * 实际执行推理的模型名称
     */
    private String model;

    /**
     * 对应每个输入文本的高维浮点向量列表（例如 1024 维）
     */
    private List<List<Double>> embeddings;

    /**
     * 推理总耗时（纳秒）
     */
    private Long totalDuration;

    /**
     * 模型加载耗时（纳秒）
     */
    private Long loadDuration;

    /**
     * 评估的 Token 数量
     */
    private Integer promptEvalCount;
}
