package com.financial.copilot.agent.core.agents;

import com.financial.copilot.agent.core.service.DeepSeekClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * <h1>投研报告生成主编 Agent (Report Synthesizer)</h1>
 * <p>
 * 职责：作为多智能体复合投研流水线的首席主编（CIO 角色），整合黑板中沉淀的各阶段客观事实底座
 * （标的初筛池、多维量化评分、决赛圈对标、底层持仓与季报观点文本），
 * 严格遵循 Tool-as-Truth 纪律，生成专业、客观、严谨的结构化投研深度 Markdown 报告。
 * 支持同步阻塞输出与 SSE 响应式流式输出。
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
     * 首席投资总监 (CIO) 级别研报主编 System Prompt
     */
    private static final String SYSTEM_PROMPT = """
        你是一位兼具买方独立视角与卖方专业深度的首席投资总监 (Chief Investment Officer) 兼资深资产配置专家。
        请根据下文中提供的流水线全阶段【真实金融量化指标】、【多维评测数据】与【定性投研观点】，撰写一份符合顶级金融机构标准的深度研报。
        
        【严格防幻觉与合规纪律 (Tool-as-Truth)】:
        1. 报告中引用的收益率、夏普比率、最大回撤、行业权重、财务指标等数字必须严格与输入事实一致，严禁任何形式的虚构或自由发挥。
        2. 若输入事实中缺少某项数据，应客观标注“数据暂未披露”，严禁主观臆断。
        
        【专业研报结构要求 (标准 Markdown 格式)】:
        # 1. 投研决策执行复盘 (Executive Pipeline Summary)
        - 明确交代本次投研流水线的决策逻辑（初筛标的池 -> 多维综合能力评估 -> 决赛圈深度对标 -> 最终配置建议）；
        - 简明扼要给出最终胜出标的与核心推荐理由。
        
        # 2. 标的风险-收益与基本面对照表 (Performance & Valuation Benchmark)
        - 必须使用标准 Markdown 表格排版，横向对齐对比标的的量化指标（如：近3年年化收益、最大回撤、夏普比率、卡玛比率、估值水平等）。
        
        # 3. 底层持仓穿透与风格归因 (Portfolio & Style Attribution)
        - 若涉及公募基金：深度穿透前十大重仓股、行业集中度与风格漂移（大盘成长/价值/平衡）；
        - 若涉及个股/其他资产：剖析商业模式护城河、ROE盈利质量、估值安全边际与系统性风险（Beta）。
        
        # 4. 定性投研分析与言行一致性研判 (Manager Philosophy & Consistency)
        - 深度解读基金经理/管理人的定期报告观点与市场展望，评估其投资理念与实际持仓运作的“言行一致性”。
        
        # 5. 适格投资者画像与资产配置实施方案 (Asset Allocation & Portfolio Strategy)
        - 针对不同风险承受能力（保守型/平衡型/进取型）的适格投资者给出适配度说明；
        - 提供具体的仓位配置比例（例如：核心底仓 60% + 弹性卫星仓 40%）及动态再平衡纪律。
        
        文末务必附带清晰的【风险提示】（如权益资产波动风险、行业政策风险）与【数据溯源来源说明】。
        """;

    /**
     * 构造函数，注入大模型调用客户端
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
