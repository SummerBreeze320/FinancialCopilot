package com.financial.copilot.data.rag.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.domain.shared.rag.port.RagEmbeddingPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * <h1>本地 Ollama 1024 维 Embedding 服务适配器</h1>
 * 基于 qwen3-embedding:0.6b 生成 1024 维高维向量。
 */
@Slf4j
@Component
public class OllamaEmbeddingAdapter implements RagEmbeddingPort {

    private final String baseUrl;
    private final String modelName;
    private final int batchSize;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OllamaEmbeddingAdapter(
            @Value("${copilot.rag.embedding.base-url:http://127.0.0.1:11434}") String baseUrl,
            @Value("${copilot.rag.embedding.model:qwen3-embedding:0.6b}") String modelName,
            @Value("${copilot.rag.embedding.batch-size:32}") int batchSize
    ) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.modelName = modelName;
        this.batchSize = batchSize > 0 ? batchSize : 32;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        log.info("[RAG-EMBEDDING] 初始化 OllamaEmbeddingAdapter: baseUrl={}, model={}, batchSize={}",
                this.baseUrl, this.modelName, this.batchSize);
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[1024];
        }
        List<float[]> results = batchEmbed(List.of(text));
        if (results.isEmpty()) {
            throw new IllegalStateException("Ollama embedding 推理返回为空: text=" + text);
        }
        return results.get(0);
    }

    @Override
    public List<float[]> batchEmbed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        List<float[]> allEmbeddings = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> chunk = texts.subList(i, end);
            List<float[]> chunkResult = callOllamaWithRetry(chunk, 3);
            allEmbeddings.addAll(chunkResult);
        }

        return allEmbeddings;
    }

    private List<float[]> callOllamaWithRetry(List<String> inputChunk, int maxRetries) {
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                OllamaEmbeddingRequest requestPayload = OllamaEmbeddingRequest.builder()
                        .model(modelName)
                        .input(inputChunk)
                        .truncate(false)
                        .build();

                String jsonBody = objectMapper.writeValueAsString(requestPayload);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/embed"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new RuntimeException("Ollama API 调用失败: HTTP " + response.statusCode() + ", body: " + response.body());
                }

                OllamaEmbeddingResponse parsed = objectMapper.readValue(response.body(), OllamaEmbeddingResponse.class);
                if (parsed.getEmbeddings() == null || parsed.getEmbeddings().size() != inputChunk.size()) {
                    throw new RuntimeException("Ollama API 返回的向量数与输入不符: expected=" + inputChunk.size()
                            + ", actual=" + (parsed.getEmbeddings() == null ? 0 : parsed.getEmbeddings().size()));
                }

                List<float[]> result = new ArrayList<>(parsed.getEmbeddings().size());
                for (List<Double> doubleList : parsed.getEmbeddings()) {
                    float[] floatVec = new float[doubleList.size()];
                    for (int j = 0; j < doubleList.size(); j++) {
                        floatVec[j] = doubleList.get(j).floatValue();
                    }
                    if (floatVec.length != 1024) {
                        log.warn("[RAG-EMBEDDING] 警告: 返回向量维度不是 1024! actual={}", floatVec.length);
                    }
                    result.add(floatVec);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                log.warn("[RAG-EMBEDDING] Ollama API 第 {} 次调用失败 (共 {} 次): {}", attempt, maxRetries, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(attempt * 1000L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new RuntimeException("Ollama Embedding 调用重试耗尽仍然失败", lastException);
    }
}
