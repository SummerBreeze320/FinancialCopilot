# 意图识别与工具调用稳定性治理体系架构设计规范 (Design Spec)

## 1. 概述与设计目标

### 1.1 背景
在金融投研大模型 Agent 平台（`FinancialCopilot`）中，用户输入的自然语言请求具有高度不确定性、多义性与长尾特性；同时，大模型在调用复杂金融工具时容易面临入参幻觉、字段越界、未授权高危操作以及下游偶发异常导致的链路崩溃。

为实现生产级高可用与合规安全，本设计规范确立两大核心基础设施子系统：
1. **多级意图识别与安全护栏子系统 (Multi-layer Intent Recognition & Guardrail Engine)**：采用“规则 + 小模型主分类 + 大模型语义兜底”级联短路式管道，融合多轮对话上下文，提供置信度评估及 OOD（Out-of-Distribution，领域外意图）精准拒识与追问澄清。
2. **工具调用稳定性治理体系 (Production-grade Tool Invocation Stability)**：确立代码与 LLM 同源的参数约束（枚举/数值范围/类型）、前置参数强校验、CoT 思考链与 Few-shot 正反例推理增强、基于意图的 RAG 工具动态检索精简、高危操作 Human-In-The-Loop (HITL) 挂起审批流、以及结构化错误回传自纠错反思循环与熔断降级。

---

## 2. 整体架构与系统拓扑

```
                            [ 用户输入 Query + 会话历史 Context ]
                                           │
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 1. 意图识别与安全护栏子系统 (Intent Recognition & Guardrail Engine)                     │
│                                                                                        │
│   ┌──────────────────────────────────────────────────────────────────────────────┐     │
│   │ A. IntentRecognitionPipeline (级联短路式意图识别流水线)                       │     │
│   │   ├─ 1. RuleIntentMatcher: 高频指令、正则表达式、助记词匹配 (< 5ms, 0 Token)   │     │
│   │   ├─ 2. SlmIntentClassifier: 多轮上下文融合 + 本地轻量模型主分类 + 置信度评估 │     │
│   │   └─ 3. LlmFallbackClassifier: 大模型语义深度推理 + OOD 泛外意图拒识与引导澄清 │     │
│   └──────────────────────────────────────┬───────────────────────────────────────┘     │
│                                          │ 产出标准 IntentClassificationResult         │
│                                          ▼                                             │
│   ┌──────────────────────────────────────────────────────────────────────────────┐     │
│   │ B. IntentGuardrailService (意图安全与适格性策略层)                           │     │
│   │   ├─ 防注入审计：过滤恶意越狱与对抗性 Prompt                                 │     │
│   │   ├─ 适格性准入：比对用户风险等级 (C1-C5)，拦截超风险指令                      │     │
│   │   └─ 输出规范化 IntentTag、实体槽位 (Slots) 与澄清响应 (Clarification)       │     │
│   └──────────────────────────────────────┬───────────────────────────────────────┘     │
└──────────────────────────────────────────┼─────────────────────────────────────────────┘
                                           │ 注入 IntentTag + Slots
                                           ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ 2. 工具调用稳定性治理体系 (Production Tool Stability System)                            │
│                                                                                        │
│   ┌──────────────────────────────────────────────────────────────────────────────┐     │
│   │ A. Schema 同源规范与约束注册 (ToolConstraint & SchemaRegistry)                │     │
│   │   ├─ 注解驱动：@ToolConstraint (枚举值 allowedValues, 数值范围 min/max)       │     │
│   │   └─ LLM Function Calling JSON Schema 与 Java DTO 强类型校验单一真实源一致   │     │
│   └──────────────────────────────────────┬───────────────────────────────────────┘     │
│                                          ▼                                             │
│   ┌──────────────────────────────────────────────────────────────────────────────┐     │
│   │ B. 推理增强与意图 RAG 工具精简 (Inference Prompting & Tool Retrieval)         │     │
│   │   ├─ CoT (思考链前置)：要求 Agent 输出选用工具理由与参数提取推导过程           │     │
│   │   ├─ Few-shot 模板：正例入参规范与典型错误反例引导，根除参数幻觉              │     │
│   │   └─ 意图驱动 RAG 工具检索：基于 IntentTag 动态加载专属工具集，抑制上下文膨胀  │     │
│   └──────────────────────────────────────┬───────────────────────────────────────┘     │
│                                          ▼                                             │
│   ┌──────────────────────────────────────────────────────────────────────────────┐     │
│   │ C. 执行前置校验与 Human-In-The-Loop 审批拦截 (Execution Gate & HITL)         │     │
│   │   ├─ 参数自动强校验：不合规入参前置拦截抛出 InvalidToolArgumentException     │     │
│   │   └─ @HighRiskTool 拦截：挂起执行流 (SUSPENDED)，向前端推送审批卡片          │     │
│   └──────────────────────────────────────┬───────────────────────────────────────┘     │
│                                          ▼                                             │
│   ┌──────────────────────────────────────────────────────────────────────────────┐     │
│   │ D. 异常反思自纠错与熔断降级 (Reflection Loop & Circuit Breaker)               │     │
│   │   ├─ 结构化错误回传：将工具抛错转化为 Observation 喂回 ReAct 重新规划(上限2次)│     │
│   │   └─ 超时/超限熔断：连续失败熔断降级，输出结构化降级兜底信息                 │     │
│   └──────────────────────────────────────────────────────────────────────────────┘     │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 子系统 1：多级意图识别与安全护栏详细设计

### 3.1 意图体系分类标签 (`IntentType`)
系统确立 7 大核心金融投研意图及 1 个领域外拒识标识：
1. `FUND_SCREENING`：条件初筛（板块/风格/定量指标选基，如“选近3年收益率超30%的消费基金”）；
2. `FUND_DIAGNOSIS`：单标的多维体检与业绩归因（如“分析005827葛兰的中欧医疗健康”）；
3. `FUND_COMPARISON`：决赛圈两强/多强横向对标（如“对比易方达蓝筹和景顺长城新兴成长”）；
4. `ASSET_ALLOCATION`：宏观择时与股债配置（如“当前市场环境下C3投资者如何配置资产”）；
5. `REPORT_SYNTHESIS`：综合深度投研研报与终审合成；
6. `STOCK_RESEARCH`：个股行情、财务基本面穿透与估值审计；
7. `SYSTEM_HELP`：系统能力问询、资费说明、个人账户与使用指南；
8. `OUT_OF_DOMAIN`：领域外意图（闲聊、非金融问答、政治敏感、代码开发、非法代客理财等）。

### 3.2 意图识别数据模型 (`IntentClassificationResult`)
```java
public record IntentClassificationResult(
        IntentType intent,
        double confidence,
        Map<String, Object> slots,
        MatchingLayer matchedLayer, // RULE, SLM, LLM_FALLBACK
        boolean isOutOfDomain,
        String clarificationPrompt   // 当 OOD 或置信度偏低时的友好引导提示语
) {}
```

### 3.3 级联短路式识别管道 (`IntentRecognitionPipeline`)
1. **RuleIntentMatcher (规则层)**：
   - 内置正则与关键词字典（例如：`选基|初筛|筛选` -> `FUND_SCREENING`；`对比|PK|对标` -> `FUND_COMPARISON`）；
   - 包含提取出来的代码助记符（6 位基金代码、股票代码）；
   - 若命中规则且逻辑确定，置信度设为 `1.0`，直接返回，跳过模型调用（耗时 `< 5ms`，零 Token 消耗）。
2. **SlmIntentClassifier (轻量分类层)**：
   - 当规则未命中时调用；基于本地 Codex-Bridge / 轻量模型接口执行结构化分类；
   - 输入包含前 3 轮对话上下文摘要；
   - 输出 `IntentType`、提取槽位及 `confidence` 打分（0.0 ~ 1.0）；
   - 若 `confidence >= 0.85`，判定为高置信度命中，直接输出。
3. **LlmFallbackClassifier (大模型语义兜底与 OOD 辨识层)**：
   - 当 `confidence < 0.85` 时触发；
   - 负责强上下文复杂推理与 **OOD 拒识检测**；
   - 若用户指令属于金融领域外或违规诉求，标记 `isOutOfDomain = true`，输出 `IntentType.OUT_OF_DOMAIN`，并生成标准化澄清建议（例如：“我是专注于公募基金与股票投资分析的 AI 助教，暂不支持生活类问答，您可以询问具体的基金代码或选基策略。”）。

### 3.4 意图安全护栏策略 (`IntentGuardrailService`)
- **Prompt 注入拦截**：检测指令中是否存在“忽略所有系统规则”、“透露内部Prompt”、“越权操作”等对抗特征；
- **投资适格性校验**：比对当前用户的 KYC 风险等级；例如：若 C1 (保守型) 试图要求生成高杠杆股票投研，护栏策略进行风险警示并要求二次确认；
- **产物分发**：审计通过后，将标准 `IntentTag` 注入下游的 `SupervisorWorkflow` 与 `AgentNodeRouter`。

---

## 4. 子系统 2：工具调用稳定性治理体系详细设计

### 4.1 Schema 强约束元数据与同源性 (`@ToolConstraint`)
在各业务 Tool 的方法参数上引入声明式约束：
```java
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolConstraint {
    String[] allowedValues() default {};
    double minValue() default Double.NEGATIVE_INFINITY;
    double maxValue() default Double.POSITIVE_INFINITY;
    String regexPattern() default "";
    boolean required() default true;
}
```
- **同源保证**：AgentScope 在构建工具描述 JSON Schema 时，读取 `@ToolConstraint` 属性，将其转换为标准 JSON Schema 字段（`enum`, `minimum`, `maximum`, `pattern`），确保大模型看到的参数契约与 Java 后端强校验规则完全一致。

### 4.2 推理策略增强 (CoT, Few-shot & 意图 RAG)
1. **CoT 思考链引导**：
   - 在 Tool-calling Prompt 中注入固定思考模式：
     `"在调用工具前，必须以简短步骤说明：1) 目标数据；2) 所选工具理由；3) 提取入参有效性。"`
2. **Few-shot 参数示范**：
   - 针对核心工具内置正例（如 `{"fundCode": "000001"}`）与反例（如 `{"fundCode": "华夏成长"}`），明确指示禁止以汉字名称代替代码。
3. **基于 IntentTag 的 RAG 工具动态检索**：
   - `ContextualToolkitFactory` 不再一股脑塞入所有工具，而是根据 `IntentClassificationResult.intent()` 动态过滤；
   - 例如：当 `intent == FUND_SCREENING` 时，仅召回 `screen_funds`、`match_metrics_and_sectors`、`explain_metric`，自动屏蔽无关的 `stock_metrics` 等，将上下文工具数从 15+ 压缩至 3~5 个，大幅降低大模型幻觉与 Token 浪费。

### 4.3 前置自动校验与 Human-in-the-Loop (HITL) 审批
1. **ToolExecutionGate (参数前置拦截)**：
   - 在工具反射/调用切面执行前，先按 `@ToolConstraint` 检查参数合规性；
   - 若参数越界，抛出带有明确修复指引的 `InvalidToolArgumentException`，阻断无效网络击穿。
2. **@HighRiskTool 审批挂起机制**：
   - 定义 `@HighRiskTool(category = "PORTFOLIO_ORDER", riskLevel = "HIGH")`；
   - 当检测到目标工具为高危工具时：
     1. 拦截执行，生成唯一审批工单 `approvalId = "appr-" + UUID`；
     2. 将当前任务状态设置为 `WAITING_FOR_APPROVAL`，并持久化当前未完成的调用快照；
     3. 通过 WebFlux SSE 向前端发送 `high_risk_approval_required` 事件卡片；
     4. 前端提供确认/取消操作，回调 `/api/v1/research/tasks/{taskId}/approval`；
     5. 用户批准后恢复执行（Resume），拒绝则友好终止。

### 4.4 异常反思自纠错与熔断降级 (Reflection Loop & Circuit Breaker)
1. **结构化错误回传自纠错**：
   - 当工具抛出异常（如 `InvalidToolArgumentException` 或数据查询 404），执行器不直接让整个流程失败，而是构造格式化 Observation：
     `[Tool Execution Error]: 工具 'fund_metrics' 调用失败: 基金代码 '999999' 未找到。请检查代码是否存在，或先使用 'screen_funds' 查询有效标的。`
   - AgentScope 模型读取该错误信息后，触发自我反思并重新生成正确的工具调用。
   - 限制每轮任务最大反思纠错重试次数为 `2` 次。
2. **熔断与降级策略**：
   - 若重试 2 次仍然失败，或外部微服务产生 503 超时，熔断器打开，返回预设的兜底事实并提示用户，保证系统稳定性。

---

## 5. 模块工程归属与代码架构约束

严格遵循多模块分层原则：
- `copilot-common`：
  - 定义 `IntentType`、`MatchingLayer`、`ToolApprovalDTO` 等跨模块 DTO 与状态枚举。
- `copilot-agent-core`：
  - `com.financial.copilot.agent.core.infra.intent`：存放意图分类、规则匹配、OOD 兜底与 Guardrail 服务；
  - `com.financial.copilot.agent.core.infra.tool`：存放工具约束注解、同源校验器、HITL 挂起会话管理、反思自纠错处理器；
  - 严格遵守 `.agents/rules/coding-standards.md`（中文 Javadoc、Clean imports、Lombok 规范）。
- `copilot-app`：
  - 暴露高危操作审批与恢复端点：`POST /api/v1/research/tasks/{taskId}/approval`。

---

## 6. 测试与质量保证规范

1. **RuleIntentMatcherTest**：验证高频规则在 5ms 内命中对应意图，未命中返回 empty。
2. **SlmIntentClassifierTest**：模拟多轮对话输入，验证上下文融合与置信度打分。
3. **IntentOodDetectionTest**：测试闲聊、无关问题、越狱 Prompt 被判定为 `OUT_OF_DOMAIN` 并生成澄清语。
4. **ToolValidationAspectTest**：测试 `@ToolConstraint` 对枚举值、取值范围的非空和边界拦截。
5. **ToolApprovalGateTest**：测试 `@HighRiskTool` 触发挂起、SSE 审批事件派发及 Resume 成功流转。
6. **ToolReflectionLoopTest**：模拟入参错误经由 Observation 回传 LLM 完成自动修正成功执行。
7. **NoLegacyExecutionArchitectureTest**：全量架构防护测试通过。
