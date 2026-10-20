package com.financial.copilot.gateway.catalog;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <h1>大模型厂商元数据与模型字典注册中心 (LLM Provider Registry)</h1>
 * <p>
 * 职责：集中管理平台所有已适配的大模型厂商元数据、可用模型规格列表及推荐端点，
 * 供开发测试控制台获取全景列表、动态选择基准模型。
 * </p>
 *
 * @author FinancialCopilot
 */
@Component
public class LlmProviderRegistry {

    private final Map<LlmProviderType, LlmProviderMetadata> registryMap = new LinkedHashMap<>();

    public LlmProviderRegistry() {
        initDefaultProviders();
    }

    private void initDefaultProviders() {
        // 1. DeepSeek
        registryMap.put(LlmProviderType.DEEPSEEK, new LlmProviderMetadata(
                LlmProviderType.DEEPSEEK,
                "DeepSeek (深度求索)",
                "https://api.deepseek.com/v1",
                "高性价比金融投研主力，具备极佳的中文投研理解与思维链推理能力",
                "https://platform.deepseek.com",
                List.of(
                        new LlmModelOption("deepseek-chat", "DeepSeek-V3", "通用快速大模型，适合初筛与常规体检", "64k", false, true),
                        new LlmModelOption("deepseek-reasoner", "DeepSeek-R1", "深度思维链推理大模型，适合复杂归因与研报终审", "64k", true, true)
                ),
                "deepseek-chat",
                "deepseek-reasoner"
        ));

        // 2. OpenAI
        registryMap.put(LlmProviderType.OPENAI, new LlmProviderMetadata(
                LlmProviderType.OPENAI,
                "OpenAI",
                "https://api.openai.com/v1",
                "业界标杆综合能力，用于研发 Prompt 标准对齐与高精度推理",
                "https://platform.openai.com",
                List.of(
                        new LlmModelOption("gpt-4o", "GPT-4o", "全能型旗舰大模型，综合金融对标标杆", "128k", false, true),
                        new LlmModelOption("gpt-4o-mini", "GPT-4o-mini", "高吞吐轻量大模型", "128k", false, true),
                        new LlmModelOption("o1", "OpenAI o1", "深度思维链高阶推理模型", "128k", true, false),
                        new LlmModelOption("o3-mini", "OpenAI o3-mini", "低延迟推理模型，支持三档思考预算", "128k", true, true)
                ),
                "gpt-4o",
                "o3-mini"
        ));

        // 3. Qwen (阿里百炼)
        registryMap.put(LlmProviderType.QWEN, new LlmProviderMetadata(
                LlmProviderType.QWEN,
                "Qwen (通义千问)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "阿里百炼大模型，国内合规首选，优秀金融实体理解与大规模并发支持",
                "https://bailian.console.aliyun.com",
                List.of(
                        new LlmModelOption("qwen-plus", "Qwen-Plus", "主力平衡型大模型，性能与成本均衡", "128k", false, true),
                        new LlmModelOption("qwen-max", "Qwen-Max", "千问系列旗舰模型，复杂推理能力极强", "32k", false, true),
                        new LlmModelOption("qwq-32b", "Qwen-QwQ", "通义思维链深度推理模型", "32k", true, true),
                        new LlmModelOption("qwen-turbo", "Qwen-Turbo", "极速低成本模型，适合高频初筛", "128k", false, true)
                ),
                "qwen-plus",
                "qwq-32b"
        ));

        // 4. Zhipu (智谱清言)
        registryMap.put(LlmProviderType.ZHIPU, new LlmProviderMetadata(
                LlmProviderType.ZHIPU,
                "Zhipu (智谱清言)",
                "https://open.bigmodel.cn/api/paas/v4",
                "清华系自主研发大模型，企业级合规与中文专业金融知识底座",
                "https://open.bigmodel.cn",
                List.of(
                        new LlmModelOption("glm-4-plus", "GLM-4-Plus", "智谱旗舰大模型，高质量长文本处理", "128k", false, true),
                        new LlmModelOption("glm-4-flash", "GLM-4-Flash", "高速免费版轻量模型", "128k", false, true),
                        new LlmModelOption("glm-zero-preview", "GLM-Zero", "智谱推理思维链模型", "128k", true, true)
                ),
                "glm-4-plus",
                "glm-zero-preview"
        ));

        // 5. Ollama (本地私有化)
        registryMap.put(LlmProviderType.OLLAMA, new LlmProviderMetadata(
                LlmProviderType.OLLAMA,
                "Ollama (本地离线)",
                "http://localhost:11434/v1",
                "私有化离线大模型运行引擎，零数据外流风险，支持金融内网完全隔离部署",
                "https://ollama.com",
                List.of(
                        new LlmModelOption("deepseek-r1:8b", "DeepSeek-R1-8B", "本地轻量级思维链量化模型", "32k", true, true),
                        new LlmModelOption("deepseek-r1:14b", "DeepSeek-R1-14B", "本地平衡型思维链模型", "32k", true, true),
                        new LlmModelOption("qwen2.5:7b", "Qwen2.5-7B", "通义千问本地轻量指令模型", "32k", false, true),
                        new LlmModelOption("llama3.3:70b", "Llama3.3-70B", "Meta 开源大模型主力版", "128k", false, true)
                ),
                "deepseek-r1:8b",
                "deepseek-r1:8b"
        ));

        // 6. Custom
        registryMap.put(LlmProviderType.CUSTOM, new LlmProviderMetadata(
                LlmProviderType.CUSTOM,
                "Custom (自定义兼容)",
                "",
                "完全自定义的 OpenAI 兼容端点服务 (如 vLLM, FastChat, OneAPI, 自建反代等)",
                "",
                Collections.emptyList(),
                "",
                ""
        ));
    }

    /**
     * 获取所有已注册的厂商元数据列表
     *
     * @return 厂商元数据只读列表
     */
    public List<LlmProviderMetadata> getAllProviders() {
        return List.copyOf(registryMap.values());
    }

    /**
     * 根据厂商类型枚举获取元数据
     *
     * @param type 厂商类型枚举
     * @return 厂商元数据 Optional
     */
    public Optional<LlmProviderMetadata> getProvider(LlmProviderType type) {
        return Optional.ofNullable(registryMap.get(type));
    }

    /**
     * 根据厂商字符串标识获取元数据
     *
     * @param typeName 厂商字符串
     * @return 厂商元数据 Optional
     */
    public Optional<LlmProviderMetadata> getProvider(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Optional.empty();
        }
        for (Map.Entry<LlmProviderType, LlmProviderMetadata> entry : registryMap.entrySet()) {
            if (entry.getKey().name().equalsIgnoreCase(typeName.trim())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * 获取指定厂商支持的模型列表
     *
     * @param type 厂商类型枚举
     * @return 模型规格列表
     */
    public List<LlmModelOption> getModelsForProvider(LlmProviderType type) {
        return getProvider(type)
                .map(LlmProviderMetadata::supportedModels)
                .orElse(Collections.emptyList());
    }
}
