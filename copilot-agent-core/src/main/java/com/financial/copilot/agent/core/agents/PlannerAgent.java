package com.financial.copilot.agent.core.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.financial.copilot.agent.core.llm.service.LlmService;
import com.financial.copilot.common.enums.AssetCategory;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <h1>金融意图识别与任务规划主管 Agent (Planner Agent)</h1>
 * <p>
 * 职责：作为投研系统的第一道认知门户，精通公募基金 (FUND)、股票 (STOCK)、期货 (FUTURES)
 * 与银行理财 (WEALTH_MANAGEMENT) 等多金融大类。
 * 负责从用户自然语言诉求中识别资产类别、判断是否为复杂多步任务，并抽取关键实体（主标的代码、对标代码、经理、板块主题、风险偏好）。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class PlannerAgent {

    /**
     * 大模型统一调用服务
     */
    private final LlmService clientService;

    /**
     * JSON 对象序列化与反序列化工具
     */
    private final ObjectMapper objectMapper;

    /**
     * 优化后的多金融资产规划主管 System Prompt
     */
    private static final String SYSTEM_PROMPT = """
        你是一个精通多金融资产大类（公募基金 FUND、A股及港股股票 STOCK、期货衍生品 FUTURES、银行理财 WEALTH_MANAGEMENT）的首席投研主管兼任务规划专家 PlannerAgent。
        请对用户的提问进行深度认知分析，识别资产大类、业务意图并提取关键结构化实体。
        
        【资产大类枚举 assetCategory】:
        - FUND: 公募基金/ETF/FOF (如提及'基金'、'医药基金'、'张坤'、'回撤小于15%'等)
        - STOCK: 股票/个股 (如提及'股票'、'个股'、'茅台'、'市盈率'、'A股龙头'等)
        - FUTURES: 期货/期权/衍生品 (如提及'股指期货'、'螺纹钢'、'保证金'等)
        - WEALTH_MANAGEMENT: 银行理财/固收+ (如提及'理财产品'、'PR2'、'现金管理'等)
        
        【意图类型枚举 intent】:
        - SCREENING: 条件筛选/寻找一批标的池
        - SINGLE_ANALYSIS: 单个标的或单个投资经理的深度体检分析
        - COMPARISON: 双标的或多标的横向对标与风格差异对比
        - COMPOSITE_PIPELINE: 复合多阶段长链路流水线 (如: '先筛选...再分析前5...比较最优秀的两个...最后生成投资建议')
        - GENERAL_QA: 基础金融概念问答
        
        必须输出严格合法的 JSON 格式，禁止输出任何多余的 Markdown 或说明文字:
        {
          "assetCategory": "FUND|STOCK|FUTURES|WEALTH_MANAGEMENT",
          "intent": "SCREENING|SINGLE_ANALYSIS|COMPARISON|COMPOSITE_PIPELINE|GENERAL_QA",
          "isComplex": true或false (若涉及两步以上流水线则为 true),
          "primaryCode": "提取到的首选代码或代表代码 (如 005827 或 600519，没有则为 null)",
          "secondaryCode": "提取到的次选或对标代码 (如 161005 或 000858，没有则为 null)",
          "managerName": "提取到的经理或管理人员姓名 (没有则为 null)",
          "sectorTheme": "提取到的行业、概念或板块主题 (如 医药、消费、半导体，没有则为 null)",
          "riskPreference": "CONSERVATIVE|BALANCED|AGGRESSIVE (未提及则为 BALANCED)"
        }
        """;

    /**
     * 构造函数，自动装配大模型服务与 JSON 解析器
     *
     * @param clientService 大模型调用服务
     * @param objectMapper  JSON 解析器
     */
    public PlannerAgent(LlmService clientService, ObjectMapper objectMapper) {
        this.clientService = clientService;
        this.objectMapper = objectMapper;
    }

    /**
     * 结构化规划解析结果载体
     */
    @Data
    @Builder
    public static class PlanResult {
        /**
         * 涉及的金融资产大类
         */
        private AssetCategory assetCategory;

        /**
         * 识别意图枚举 (SCREENING / SINGLE_ANALYSIS / COMPARISON / COMPOSITE_PIPELINE / GENERAL_QA)
         */
        private String intent;

        /**
         * 是否属于复合多阶段任务
         */
        private boolean isComplex;

        /**
         * 主标的代码（如 "005827" 或 "600519"）
         */
        private String primaryCode;

        /**
         * 对标辅助标的代码（如 "161005"）
         */
        private String secondaryCode;

        /**
         * 涉及的投资经理姓名（如 "张坤"、"朱少醒"）
         */
        private String managerName;

        /**
         * 涉及的行业或主题板块（如 "医药"、"消费"）
         */
        private String sectorTheme;

        /**
         * 提取的投资者风险偏好
         */
        private String riskPreference;

        /**
         * 用户原始自然语言 Prompt
         */
        private String rawUserPrompt;
    }

    /**
     * 解析用户输入并生成金融意图规划
     *
     * @param userPrompt 用户原始输入
     * @return 规划结果封装对象 {@link PlanResult}
     */
    public PlanResult plan(String userPrompt) {
        log.info("[PLANNER] 正在启动金融意图深度认知与规划: prompt={}", userPrompt);
        String response = clientService.chat(SYSTEM_PROMPT, userPrompt);
        try {
            String cleanJson = response.trim();
            if (cleanJson.startsWith("```json")) {
                cleanJson = cleanJson.substring(7);
            }
            if (cleanJson.endsWith("```")) {
                cleanJson = cleanJson.substring(0, cleanJson.length() - 3);
            }
            JsonNode node = objectMapper.readTree(cleanJson.trim());

            String categoryStr = node.path("assetCategory").asText("FUND");
            AssetCategory category;
            try {
                category = AssetCategory.valueOf(categoryStr.toUpperCase());
            } catch (Exception e) {
                category = AssetCategory.FUND;
            }

            boolean complex = node.path("isComplex").asBoolean(false);
            String intent = node.path("intent").asText("SINGLE_ANALYSIS");
            if ("COMPOSITE_PIPELINE".equalsIgnoreCase(intent)) {
                complex = true;
            }

            return PlanResult.builder()
                    .assetCategory(category)
                    .intent(intent)
                    .isComplex(complex)
                    .primaryCode(node.path("primaryCode").asText("005827"))
                    .secondaryCode(node.path("secondaryCode").asText("161005"))
                    .managerName(node.path("managerName").asText(null))
                    .sectorTheme(node.path("sectorTheme").asText(null))
                    .riskPreference(node.path("riskPreference").asText("BALANCED"))
                    .rawUserPrompt(userPrompt)
                    .build();
        } catch (Exception e) {
            log.warn("[PLANNER] 解析响应 JSON 失败，启用金融规则匹配兜底: response={}", response);
            AssetCategory category = AssetCategory.FUND;
            if (userPrompt.contains("股票") || userPrompt.contains("个股") || userPrompt.contains("A股")) {
                category = AssetCategory.STOCK;
            }

            String intent = "SINGLE_ANALYSIS";
            boolean complex = false;
            if (userPrompt.contains("然后") || userPrompt.contains("再") || userPrompt.contains("最后")) {
                intent = "COMPOSITE_PIPELINE";
                complex = true;
            } else if (userPrompt.contains("对比") || userPrompt.contains("与") || userPrompt.contains("和")) {
                intent = "COMPARISON";
            } else if (userPrompt.contains("选") || userPrompt.contains("找") || userPrompt.contains("推荐")) {
                intent = "SCREENING";
            }

            return PlanResult.builder()
                    .assetCategory(category)
                    .intent(intent)
                    .isComplex(complex)
                    .primaryCode(category == AssetCategory.STOCK ? "600519" : "005827")
                    .secondaryCode(category == AssetCategory.STOCK ? "000858" : "161005")
                    .rawUserPrompt(userPrompt)
                    .build();
        }
    }
}
