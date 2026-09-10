package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.service.DeepSeekClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * <h1>投研报告生成主编 Agent (Report Synthesizer)</h1>
 * <p>
 * 职责：作为多智能体投研管线的终审主编，结合黑板中沉淀的各项事实底座（量化指标、前十大重仓、季报原文观点），
 * 生成严格防幻觉、符合公募/券商研究所专业标准的 Markdown 格式投研报告。支持阻塞式全量输出与 SSE 响应式流式输出。
 * </p>
 *
 * @author FinancialCopilot
 */
@Slf4j
@Component
public class ReportSynthesizer {

    /**
     * 大模型客户端服务
     */
    private final DeepSeekClientService clientService;

    /**
     * 投研总监主编 System Prompt
     */
    private static final String SYSTEM_PROMPT = """
        你是一位公募基金独立投研总监兼资深 FOF 投资经理。
        请根据下文中提供的【真实金融量化指标】与【季报定性观点】，撰写一份客观、严谨、立体的专业投研深度报告。
        
        【严格防幻觉纪律】:
        1. 报告中引用的收益率、夏普比率、最大回撤、行业权重等数字必须与输入事实一致，严禁自行胡编乱造。
        2. 若输入事实中缺少某项数据，客观说明“暂无披露”，不得推断猜想。
        
        【报告结构要求】:
        # 1. 核心结论速览 (Executive Summary)
        # 2. 风险-收益全景对标表 (Markdown 表格对齐)
        # 3. 风格特征与底层持仓穿透剖析 (大盘/小盘、行业轮动、集中度)
        # 4. 基金经理投资哲学与言行一致性研判 (解读季报定性表态)
        # 5. 适格投资者画像与资产配置建议
        
        文末务必附带客观合规风险提示与数据溯源来源标注。
        """;

    /**
     * 构造函数
     *
     * @param clientService 大模型客户端服务
     */
    public ReportSynthesizer(DeepSeekClientService clientService) {
        this.clientService = clientService;
    }

    /**
     * 同步生成完整报告文本
     *
     * @param factualContext 事实上下文（由各 Agent 收集并汇总于黑板中的客观数据）
     * @param userGoal       用户原始研究诉求
     * @return 深度 Markdown 研报文本
     */
    public String synthesize(String factualContext, String userGoal) {
        String prompt = "【用户诉求】: " + userGoal + "\n\n" + factualContext;
        return clientService.chat(SYSTEM_PROMPT, prompt);
    }

    /**
     * SSE 响应式流式输出报告（Token 级平滑推送）
     *
     * @param factualContext 事实上下文
     * @param userGoal       用户原始研究诉求
     * @return 响应式 Token 数据流 Flux
     */
    public Flux<String> synthesizeStream(String factualContext, String userGoal) {
        String prompt = "【用户诉求】: " + userGoal + "\n\n" + factualContext;
        return clientService.chatStream(SYSTEM_PROMPT, prompt);
    }
}
