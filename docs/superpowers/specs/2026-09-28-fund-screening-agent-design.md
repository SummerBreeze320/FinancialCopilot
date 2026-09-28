# 基金筛选 Agent 设计

- **状态**：待评审
- **日期**：2026-09-28
- **范围**：独立 Python 服务；不改动现有 Java 模块

## 1. 目标与边界

服务把中文基金筛选需求转换为数据引擎可执行的 `fund-screening/v1` JSON DSL。它解决自由推理直接生成 DSL 时的指标误识别、参数遗漏、逻辑混乱与不可校验问题。

服务提供 FastAPI HTTP 和 WebSocket 两种入口。调用方在每次追问时完整传入上轮 DSL；服务不持久化 DSL 的业务真相，也不把内部候选、版本或节点标识交给数据引擎。

本轮 MVP 使用本地 Codex 桥接的低成本 LLM，Python、AgentScope、Milvus、Embedding、Redis、sa-token 和 WebSocket 的部署适配保留为明确接口。指标与板块初始数据来自：

- `D:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\.worktrees\lite-mysql\docs\temp\metrics.json`
- `D:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\.worktrees\lite-mysql\docs\temp\sectors.json`

## 2. 最终 DSL 契约

最终交付物只包含执行语义：不含 `asset`、知识域版本、候选得分、内部 ID、修复记录或调试信息。顶层是逻辑组，组合若干业务筛选组；顶层 `sort` 与 `limit` 决定最终返回集。

```json
{
  "version": "fund-screening/v1",
  "operator": "AND",
  "children": [
    {
      "sector": {
        "operator": "OR",
        "children": [
          {"sectorId": "1000022713000000", "match": "SELF_OR_DESCENDANT"}
        ]
      },
      "metric": {
        "operator": "AND",
        "children": [
          {"metric": "f_info_fundscale", "operator": ">", "value": 10, "unit": "BILLION_CNY"},
          {
            "operator": "OR",
            "children": [
              {"metric": "f_risk_maxdownside", "operator": ">", "value": -5, "unit": "PERCENT"},
              {"metric": "f_info_risklevel", "operator": "=", "value": 1, "unit": "ENUM"}
            ]
          }
        ]
      },
      "sort": [],
      "limit": 100
    }
  ],
  "sort": [{"metric": "f_return_1y", "direction": "DESC", "nulls": "LAST"}],
  "limit": 20
}
```

### 2.1 节点语义

- 逻辑组为 `{operator, children}`。`operator` 仅允许 `AND`、`OR`、`NOT`；`NOT` 恰好一个 child。
- 指标叶子含 `metric`、`operator`、`value`、`unit`，可选 `period`。同一指标可出现多次，每次可有不同数值、时间和操作符。
- 业务筛选组必须同时有 `sector` 与 `metric`，且可有局部 `sort`、`limit`。板块是该组的必填筛选范围，不以显式 AND 与指标拼接。
- `sector` 本身是递归逻辑树；叶子含 `sectorId` 和 `match`。板块之间可用 `AND`、`OR`、`NOT` 组合。
- 未明确板块的业务筛选组注入默认“内地公募基金”板块 `1000019220000000`，标记仅保留在内部诊断，最终 DSL 是普通 sector 叶子。
- 局部 `sort`、`limit` 在“该组板块范围内的指标结果”形成后执行，结果再参与父组逻辑计算。顶层 `sort`、`limit` 最后执行。

### 2.2 示例语义

用户的两组表达式：

```text
[半导体板块：规模 > 10 亿 AND (最大回撤 > -5% OR 风险等级 = 1)]
AND
[默认公募基金板块：成立年限 > 10 年 AND (近三月收益 > 5% OR 近一年收益 > 3%)]
```

对应顶层 `AND` 的两个业务筛选组。半导体候选绑定到 `1000022713000000`，成立年限、规模、最大回撤和风险等级分别绑定到 `f_info_existingyear`、`f_info_fundscale`、`f_risk_maxdownside`、`f_info_risklevel`。

## 3. 五段可观测编排

每次请求产生唯一 `traceId`；每段持久化或发布结构化阶段结果、耗时、路由和错误码。LLM 不得跳过候选绑定直接产出最终 DSL。

1. **`query_preprocess`（理解准备）**：规范中文数字、百分号、金额单位和时间词；装配请求及可选上轮 DSL；识别新建或编辑意图。
2. **`query_analysis_and_recall`（分析与召回）**：LLM 生成受 JSON Schema 约束的 pre-recall plan，标明条件组以及 metric、scope、entity 片段；执行三路召回，汇总 candidate pool。
3. **`command_build`（生成）**：根据候选裁决结果及 Candidate Contract 机械构建 DSL；只允许使用候选池中的 key。
4. **`validator_and_repair`（校验修复）**：验证 Schema、指标 ID、板块 ID、参数类型、单位、操作符、时间区间、排序、limit、默认板块及负值指标。可安全修复时修复并记录；有歧义时返回澄清请求。
5. **`command_finalize`（交付）**：移除内部字段，输出纯 DSL；HTTP/WebSocket 的 debug 模式另带诊断。

## 4. 三层语义与三路 RAG

### 4.1 三层语义

1. LLM 只产出 pre-recall plan，判断 query 的每个条件片段所属的 metric、scope 或 entity。
2. 按 plan 对三个域并行召回，生成候选池。
3. LLM 在候选池内裁决候选并填充参数；程序据此生成命令。

每层都有严格输入输出，能定位是解析、召回、裁决、构建或校验导致的错误。

### 4.2 三路召回

- **metrics**：Milvus 向量召回，语料包含名称、别名、描述和指标助记码。
- **scope**：Milvus 向量召回，返回 sectorId、层级、叶子状态和展开策略。
- **entities**：Lexical 文本检索，适合公司、经理、股票、指数等精确命名实体。

三路结果进入统一 candidate pool 与 Candidate Contract Index。禁止在 parser 或 validator 中堆积业务词硬编码。

## 5. Candidate Contract 与实体优先级

所有候选统一具备域、标准 key、展示名称、别名、允许操作符、值类型、支持用途和置信度。实体额外具备 `bindingRole` 与 `preferredMetric`。

```json
{
  "domain": "entity",
  "key": "manager:zhangkun",
  "name": "张坤",
  "bindingRole": "FUND_MANAGER",
  "preferredMetric": "f_info_fundmanager",
  "supportedOperators": ["="],
  "valueType": "ENTITY_REF"
}
```

因此“易方达”“张坤”“沪电股份”“沪深300”不能仅凭文本相似度绑定。Command Build 必须根据实体契约选择指标和操作符；无法唯一绑定时转为澄清，而不是猜测。

## 6. 三级路由

- **direct**：单个高置信实体、板块或指标，所有参数完整；无 LLM DSL 生成，直接机械组装并校验。
- **simple**：单个业务筛选组，条件少、仅 AND，无排除和跨组逻辑；使用轻量 LLM 进行 plan/参数填充。
- **full**：多业务筛选组、嵌套 OR/NOT、排除条件、跨组逻辑或复杂编辑；运行完整三层语义链路。

路由判定、原因和阈值记录进 trace，便于以后用真实 case 调整。

## 7. 多轮表达式编辑

请求调用方完整传入 `previousDsl`。Preprocess 判断是新建还是编辑：

- **simple edit**：删除条件、修改 limit、调整 sort/asc/desc；先以规则方式变换 AST，再经 Validator 验证。
- **complex edit**：新增或替换指标、改变板块、改变逻辑组合；重新生成 plan、召回、裁决和构建。

最终 DSL 不暴露内部节点 ID。运行时 AST 可使用临时位置索引定位编辑目标；两条完全相同的条件又无法由语言区分时，服务必须请求澄清。

## 8. API 与事件契约

### 8.1 HTTP

`POST /v1/fund-screening/commands`

```json
{
  "query": "半导体基金中筛选规模大于10亿，最大回撤低于5%的前20只",
  "previousDsl": null,
  "conversationId": "optional-client-id",
  "debug": false
}
```

成功响应含 `dsl`。`debug=true` 才含阶段摘要、候选池摘要和修复记录。需要澄清时返回机器可读 `clarification`，不返回伪可执行 DSL。

### 8.2 WebSocket

`/v1/fund-screening/ws` 接受与 HTTP 同型的请求，并依次推送：`stage_started`、`stage_completed`、`repair_applied`、`clarification_required`、`finalized`、`failed`。每个事件有 `traceId`、阶段名、时间戳和最小必要 payload；`finalized` 负载含最终 DSL。

### 8.3 运维

`GET /v1/fund-screening/health` 返回服务、Milvus、Redis、模型桥接和数据目录健康状态。

## 9. Validator 与修复策略

- 只接受 catalog 中存在且 enabled 的 metric、sector、entity；校验其支持 filter/sort、操作符和值类型。
- 检查每个业务筛选组含有效 sector 和 metric；缺省 sector 时注入默认板块。
- `limit` 必须为正整数且不大于配置上限；含局部 limit 的业务组必须有确定排序，缺省时以基金代码升序补齐。
- 校验时间范围与指标适配性。
- 最大回撤等底层负值指标：用户“回撤低于 15%”标准化为 `f_risk_maxdownside >= -15`。
- 不进行可能改变用户投资意图的猜测修复；此类问题以澄清事件返回。

## 10. RAG 数据治理与评估闭环

维护 case 集：原始 query、预期候选/DSL、实际 Top-1/Top-3、实际绑定、错误分类与修复版本。每轮收集真实 query 并复盘：别名缺失、embedding text 信息不足、向量召回差或 rerank 策略错误。治理变更可只更新 aliases、embedding 文本、索引或 rerank，无需把业务规则写回解析器。

## 11. 验收标准

1. 示例指标和板块能从给定 JSON 正确绑定，且最终 DSL 不含 `asset`、catalog 版本、调试信息或内部 ID。
2. 支持同一指标在一个表达式中用不同参数重复出现，并支持嵌套 `AND`/`OR`/`NOT`。
3. 每个业务筛选组都含板块范围；未指定时注入默认公募基金板块。
4. 多个业务筛选组可由顶层逻辑组合，并各自支持局部 `sort`、`limit`，最终结果支持顶层 `sort`、`limit`。
5. 回撤负值转换、非法指标/操作符/limit、缺失板块和不完整时间参数都能被修复或明确拒绝。
6. HTTP 与 WebSocket 给出等价最终 DSL；WebSocket 可观察五段链路。
7. simple edit 不触发全量召回；complex edit 重新走召回与绑定。
8. 评估报告能输出每个 case 的 Top-1、Top-3 与错误归因。
