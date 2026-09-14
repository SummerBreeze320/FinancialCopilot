package com.financial.copilot.data.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <h1>本地 Ollama Embedding API 请求体 (DTO)</h1>
 * <p>
 * 对应 HTTP 协议端点 <code>POST /api/embed</code>。
 * </p>
 *
 * @author FinancialCopilot
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaEmbeddingRequest {

    /**
     * 向量模型名称（默认 qwen3-embedding:0.6b，输出 1024 维）
     */
    private String model;

    /**
     * 待生成高维向量的原始文本列表（支持批量推理，建议批大小为 32）
     */
    private List<String> input;

    /**
     * 是否截断超长文本（默认 false）
     */
    @Builder.Default
    private boolean truncate = false;
}
