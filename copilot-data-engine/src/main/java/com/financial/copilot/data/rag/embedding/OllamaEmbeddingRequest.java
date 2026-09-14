package com.financial.copilot.data.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * <h1>Ollama Embedding API 请求体</h1>
 * 端点: POST /api/embed
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaEmbeddingRequest {

    /** 模型名称，默认 qwen3-embedding:0.6b */
    private String model;

    /** 待推理文本列表 */
    private List<String> input;

    /** 是否截断超长文本，默认 false */
    @Builder.Default
    private boolean truncate = false;
}
