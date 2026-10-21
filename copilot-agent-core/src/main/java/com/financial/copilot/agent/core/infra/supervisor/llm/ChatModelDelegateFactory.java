package com.financial.copilot.agent.core.infra.supervisor.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h1>Spring AI ChatModel 委托实例工厂 (ChatModelDelegateFactory)</h1>
 * <p>
 * 默认优先对接本地运行的 Codex-Bridge (http://127.0.0.1:9090/v1)；
 * 按照 (baseUrl, modelName) 非敏感指纹缓存真实客户端，防止重复构建。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class ChatModelDelegateFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelDelegateFactory.class);

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:9090/v1";
    public static final String DEFAULT_API_KEY = "local-agy";
    public static final String DEFAULT_MODEL = "gemini-3.8-flash-low";

    private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 获取或创建默认的本地 Codex-Bridge 驱动 ChatModel 实例。
     *
     * @return ChatModel 实例
     */
    public ChatModel getOrCreateDefault() {
        String baseUrl = System.getenv().getOrDefault("OPENAI_BASE_URL", DEFAULT_BASE_URL);
        String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY", DEFAULT_API_KEY);
        String model = System.getenv().getOrDefault("MODEL_NAME", DEFAULT_MODEL);
        return getOrCreate(baseUrl, apiKey, model);
    }

    /**
     * 根据指定的端点、密钥与模型名称获取或创建 ChatModel 实例。
     *
     * @param baseUrl 服务地址
     * @param apiKey 接口密钥（占位符）
     * @param model 模型名称
     * @return ChatModel 实例
     */
    public ChatModel getOrCreate(String baseUrl, String apiKey, String model) {
        String effectiveBaseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.strip();
        String effectiveApiKey = (apiKey == null || apiKey.isBlank()) ? DEFAULT_API_KEY : apiKey.strip();
        String effectiveModel = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.strip();

        String cacheKey = effectiveBaseUrl + "|" + effectiveModel;
        return cache.computeIfAbsent(cacheKey, k -> createChatModel(effectiveBaseUrl, effectiveApiKey, effectiveModel));
    }

    private ChatModel createChatModel(String baseUrl, String apiKey, String model) {
        log.info("构建 Spring AI OpenAI ChatModel 实例, baseUrl: {}, model: {}", baseUrl, model);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(model)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(chatOptions)
                .build();
    }
}
