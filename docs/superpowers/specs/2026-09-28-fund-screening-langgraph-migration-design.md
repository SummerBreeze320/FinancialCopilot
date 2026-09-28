# 基金筛选 Agent LangGraph 迁移设计

日期：2026-09-28

## 1. 目标

将当前显式 Python 编排的基金筛选编译链迁移为 LangChain + LangGraph 工作流，同时保留已经验证的 PGVector 召回、Candidate Contract、Command Builder、Validator、FastAPI、WebSocket 和本地 Codex bridge。

本次迁移不采用自由推理的 ReAct Agent。系统仍是确定性优先的 DSL 编译器：LLM 只负责语义拆解和候选受限裁决，程序负责召回、构建、校验、修复与交付。

## 2. 技术边界

- LangChain：模型适配、结构化输出和提示模板。
- LangGraph：有状态节点编排、条件分支、校验修复循环、多轮 checkpoint 和事件流。
- PostgreSQL PGVector：指标与板块向量召回。
- Lexical Retriever：实体精确匹配和文本召回。
- Redis：生产环境的 LangGraph checkpoint；MVP 测试允许内存 checkpoint。
- Codex bridge：OpenAI-compatible LLM 服务。
- FastAPI/WebSocket：同步请求和阶段事件输出。

不引入 LangChain AgentExecutor，不允许 LLM 自主选择任意工具，也不允许 LLM 跳过 Candidate Contract 直接创造指标、板块、实体或参数。

## 3. Graph State

```python
class FundScreeningState(TypedDict):
    request_id: str
    conversation_id: str | None
    query: str
    normalized_query: str
    previous_dsl: dict | None
    intent: Literal["CREATE", "EDIT"]
    edit_complexity: Literal["NONE", "SIMPLE", "COMPLEX"]
    route: Literal["direct", "simple", "full"]

    recall_plan: dict | None
    candidate_pool: list[dict]
    candidate_contract_index: dict[str, dict]
    decision: dict | None

    draft_dsl: dict | None
    final_dsl: dict | None
    validation_errors: list[dict]
    repairs: list[dict]
    clarification: dict | None
    repair_attempts: int
    trace: list[dict]
```

节点只能返回自己负责更新的字段。每个节点向 `trace` 追加阶段名、耗时、输入摘要、输出摘要、路由和错误码，不记录 API Key 或完整敏感数据。

## 4. 主工作流

```text
START
  ↓
query_preprocess
  ↓
route_classifier
  ├─ direct ────────────────────────────┐
  ├─ simple → simple_semantic_parse ────┤
  └─ full                              │
       ↓                               │
pre_recall_plan                        │
       ↓                               │
parallel_recall                        │
       ↓                               │
candidate_adjudication                 │
       ↓                               │
command_build ←────────────────────────┘
       ↓
validator
  ├─ valid → command_finalize → END
  ├─ repairable → mechanical_repair → validator
  └─ ambiguous → clarification → END
```

校验循环最多执行两次。超过上限仍不合法时返回结构化 clarification，不继续调用 LLM 猜测。

## 5. 节点职责

### 5.1 query_preprocess

执行文本归一化、上下文装载、创建/编辑意图识别和上一轮 DSL 准备。不调用 LLM；只有无法通过规则区分创建和编辑时才允许进入复杂语义路径。

### 5.2 route_classifier

- `direct`：单个高置信候选，参数完整，无 OR、NOT、跨组条件。
- `simple`：单业务组、少量 AND 条件，无复杂编辑。
- `full`：OR/NOT、多业务组、实体歧义、复杂参数、复杂编辑。

路由首先采用确定性规则和召回置信度，不默认增加第三次 LLM 调用。

### 5.3 pre_recall_plan

第一处基础 LLM 调用。使用 LangChain structured output 生成条件片段，只判断：

- condition group；
- mention；
- domain：metric、scope、entity；
- 用户明确表达的参数片段。

该节点不选择最终 catalog key，也不生成 DSL。

### 5.4 parallel_recall

按照 plan 并行执行：

- metric：PGVector + alias/lexical；
- scope：PGVector + alias/lexical；
- entity：lexical 优先，必要时扩展向量召回。

结果统一为 Candidate Pool 和 Candidate Contract Index。相同 domain/key 去重，保留命中来源、分数和排序信息。

### 5.5 candidate_adjudication

第二处基础 LLM 调用。LLM 只能使用 Candidate Contract Index 中存在的 key，并按照契约填写：

- 业务组逻辑；
- 指标比较符和值；
- 指标函数参数；
- 板块；
- 实体绑定；
- sort；
- limit。

使用 LangChain structured output 校验裁决结构。结构错误可以重试一次；业务错误交给 Validator。

### 5.6 command_build

纯机械构建，不调用 LLM。把裁决结果转换为 `fund-screening/v1` DSL，并为未指定板块的业务组加入默认板块。

### 5.7 validator 与 mechanical_repair

检查并修复：

- catalog key；
- 操作符；
- value/unit；
- 指标函数必填参数；
- 参数类型、枚举值和日期关系；
- sort；
- limit；
- 最大回撤等负值存储指标；
- 每个业务组的板块要求。

只有确定、不改变用户投资意图的规则可以自动修复。

### 5.8 command_finalize

输出最终 DSL、route、repairs、trace 和可选 clarification。HTTP 与 WebSocket 必须使用同一个已编译 Graph。

## 6. 指标函数参数契约

每个指标视为函数，catalog 使用 `parameters` 描述函数签名。参数定义不包含 `source`。

```json
{
  "mnemonic": "f_risk_alpha",
  "parameters": [
    {
      "key": "windCode",
      "valueType": "FUND_CODE",
      "required": true
    },
    {
      "key": "startDate",
      "valueType": "DATE",
      "required": true
    },
    {
      "key": "endDate",
      "valueType": "DATE",
      "required": true,
      "defaultValue": "LATEST_TRADE_DATE"
    },
    {
      "key": "indexCode",
      "valueType": "INDEX_CODE",
      "required": false,
      "defaultValue": "FUND_BENCHMARK"
    }
  ]
}
```

枚举参数内联维护候选值：

```json
{
  "key": "frequency",
  "valueType": "ENUM",
  "required": false,
  "defaultValue": "DAILY",
  "options": [
    {"label": "日频", "value": "DAILY"},
    {"label": "周频", "value": "WEEKLY"},
    {"label": "月频", "value": "MONTHLY"}
  ]
}
```

最终指标表达式使用 `params`：

```json
{
  "metric": "f_risk_alpha",
  "operator": ">",
  "value": 1,
  "unit": "NUMBER",
  "params": [
    {"key": "startDate", "value": "2023-09-28"},
    {"key": "endDate", "value": "2026-09-28"},
    {"key": "indexCode", "value": "000300.SH"}
  ]
}
```

`windCode` 由筛选引擎在逐基金计算时绑定，可以在最终筛选 DSL 中省略；Validator 根据参数定义识别这种上下文绑定参数，不要求 LLM 填写具体基金代码。为避免重新引入 `source` 字段，该规则由参数 key/valueType 的引擎约定实现。

## 7. 多轮编辑

调用方继续完整传入上一轮 DSL。LangGraph 使用 `conversation_id` 作为 thread ID：

- 简单编辑：删除条件、修改 limit、修改排序，走确定性节点；
- 复杂编辑：新增或替换指标、改变组合逻辑、增加业务组，进入 plan/recall/adjudication；
- checkpoint 用于恢复和观测，不作为调用方省略上一轮 DSL 的理由。

## 8. WebSocket 事件

WebSocket 按节点输出：

```json
{"type":"stage","stage":"parallel_recall","status":"completed","summary":{"candidateCount":23}}
```

最终事件：

```json
{"type":"final","dsl":{},"trace":{},"clarification":null}
```

节点内部日志与外部事件共用阶段名称，便于问题定位。

## 9. 错误处理

- LLM 网络或格式失败：节点级错误，允许一次结构化重试。
- PGVector 不可用：返回依赖错误，不退化为 LLM 编造候选。
- 候选为空或歧义：返回 clarification。
- 参数缺失：存在确定默认值时补齐，否则 clarification。
- Graph 状态不合法：终止请求并记录节点及错误码。
- WebSocket 断开：取消未开始节点；正在执行的外部调用按超时回收。

## 10. 渐进迁移

1. 引入 LangChain/LangGraph 依赖和 typed state。
2. 把现有五段服务包装为 Graph Node，不改变 DSL 行为。
3. 用 Graph 替换 `RagCommandCompiler` 顶层串联，保留兼容适配器。
4. 接入 structured output 和校验重试。
5. 实现 direct/simple/full 条件边。
6. 接入多轮 checkpoint 和编辑分支。
7. 批量完善指标参数，并启用参数校验。
8. HTTP/WebSocket 切到同一 Graph 后删除旧编排入口。

每一步都要求旧测试继续通过，并增加 Graph 节点、路由、循环上限、checkpoint 和事件流测试。

## 11. 验收标准

- 当前已通过的 HTTP、WebSocket、PGVector、Builder 和 Validator 测试行为不回退。
- 一次完整请求仍只有两次基础 LLM 调用；direct 路径为零次。
- LLM 无法使用 Candidate Contract 外的 key 或参数。
- 每个业务组有板块，未指定时使用默认板块。
- 指标参数缺失、类型错误、非法枚举和日期倒置可被检测。
- 校验修复循环不会无限执行。
- HTTP 与 WebSocket 对同一状态产生等价 DSL。
- 多轮简单编辑不需要重新执行完整 RAG。
- 全链路 trace 能定位每个节点的输入摘要、输出摘要、耗时和错误。
