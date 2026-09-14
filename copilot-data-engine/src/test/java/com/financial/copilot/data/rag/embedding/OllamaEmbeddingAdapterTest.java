package com.financial.copilot.data.rag.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingAdapterTest {

    @Test
    @DisplayName("验证本地实际 Ollama 嵌入服务连接与 1024 维输出")
    void testRealOllamaEmbeddingCall() {
        OllamaEmbeddingAdapter adapter = new OllamaEmbeddingAdapter(
                "http://127.0.0.1:11434",
                "qwen3-embedding:0.6b",
                32
        );

        float[] embedding = adapter.embed("近1年回报 f_return_1y");
        assertNotNull(embedding, "Embedding 向量不应为空");
        assertEquals(1024, embedding.length, "qwen3-embedding:0.6b 必须输出严格 1024 维向量");
    }

    @Test
    @DisplayName("验证批量分批推理逻辑")
    void testBatchEmbedding() {
        OllamaEmbeddingAdapter adapter = new OllamaEmbeddingAdapter(
                "http://127.0.0.1:11434",
                "qwen3-embedding:0.6b",
                5
        );

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            texts.add("测试基金指标或分类语料 " + i);
        }

        List<float[]> results = adapter.batchEmbed(texts);
        assertEquals(12, results.size(), "批量返回结果数量必须与输入一致");
        for (float[] vec : results) {
            assertEquals(1024, vec.length);
        }
    }
}
