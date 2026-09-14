package com.financial.copilot.data.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * <h1>Ollama Embedding API 响应体</h1>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OllamaEmbeddingResponse {

    private String model;

    /** 对应每个输入文本的高维向量列表 */
    private List<List<Double>> embeddings;

    /** 响应耗时等元数据 */
    private Long totalDuration;
    private Long loadDuration;
    private Integer promptEvalCount;
}
