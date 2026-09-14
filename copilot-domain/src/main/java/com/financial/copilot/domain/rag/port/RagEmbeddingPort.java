package com.financial.copilot.domain.rag.port;

import java.util.List;

/**
 * <h1>高维向量嵌入服务端口契约</h1>
 */
public interface RagEmbeddingPort {

    /**
     * 单文本向量推理
     *
     * @param text 文本
     * @return 1024 维嵌入向量
     */
    float[] embed(String text);

    /**
     * 批量文本向量推理
     *
     * @param texts 文本列表
     * @return 1024 维嵌入向量列表
     */
    List<float[]> batchEmbed(List<String> texts);
}
