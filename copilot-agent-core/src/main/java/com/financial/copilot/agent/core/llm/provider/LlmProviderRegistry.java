package com.financial.copilot.agent.core.llm.provider;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                "deepseek-chat"
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
                "gpt-4o"
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
                        new LlmModelOption("qwen-turbo", "Qwen-Turbo", "极速低成本模型，适合高频初筛", "128k", false, true)
                ),
                "qwen-plus"
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
                        new LlmModelOption("glm-4-flash", "GLM-4-Flash", "高速免费版轻量模型", "128k", false, true)
                ),
                "glm-4-plus"
        ));

        // 5. Ollama (本地私有化)
        registryMap.put(LlmProviderType.OLLAMA, new LlmProviderMetadata(
                LlmProviderType.OLLAMA,
                "Ollama (本地离线)",
                "http://localhost:11434/v1",
                "私有化离线大模型运行引擎，零数据外流风险，支持金融内网完全隔离部署",
                "https://ollama.com",
                List.of(
                        new LlmModelOption("deepseek-r1:8b", "DeepSeek-R1 (8B Local)", "本地轻量级推理蒸馏模型", "32k", true, true),
                        new LlmModelOption("qwen2.5:14b", "Qwen-2.5 (14B Local)", "本地中等规模全能模型", "32k", false, true)
                ),
                "deepseek-r1:8b"
        ));

        // 6. Custom
        registryMap.put(LlmProviderType.CUSTOM, new LlmProviderMetadata(
                LlmProviderType.CUSTOM,
                "Custom (自定义端点)",
                "",
                "支持任意符合 OpenAI API 规范的私有云网关或自建服务 (vLLM, OneAPI 等)",
                "",
                List.of(),
                ""
        ));
    }

    /**
     * 获取所有已注册的厂商元数据列表
     *
     * @return 厂商元数据只读列表
     */
    public List<LlmProviderMetadata> getAllProviders() {
        return Collections.unmodifiableList(registryMap.values().stream().toList());
    }

    /**
     * 根据厂商枚举查询元数据
     *
     * @param type 厂商类型
     * @return 厂商元数据
     */
    public LlmProviderMetadata getMetadata(LlmProviderType type) {
        return registryMap.getOrDefault(type, registryMap.get(LlmProviderType.DEEPSEEK));
    }
}
