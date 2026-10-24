# 基金筛选统一语义编译主链设计

## 目标

将现有并存的 Strict Direct、RecallPlan fallback 与 SemanticPlan 路径收敛为单一可观测编译链。所有最终 DSL 均由候选受限裁决、确定性组装和统一校验产生，避免模型自由生成业务 ID。

## 总体流程

```text
Query + previousDsl + clarificationState
  → Intent Funnel
  → Strict Rule Bypass
  → Plan LLM
  → Condition-level RAG
  → Candidate Pool + Contract Index
  → Direct / Simple / Full Router
  → Direct Build 或 Candidate-constrained Adjudication
  → Deterministic DSL Builder
  → Validator & Repair
  → DSL 或 Clarification
```

## 意图与旁路

Intent Funnel 输出 `CREATE` 或 `REBUILD`。只有纯基金代码、唯一精确实体和 `REBUILD_SIMPLE`（limit、sort、唯一条件删除）可以在 Plan LLM 前旁路。其余请求均进入统一主链。

## 计划契约

`CREATE` 使用 `SemanticPlan`：它保存 condition mention、领域、业务分组、组内/组间逻辑树、sort 与 limit，不得包含业务 ID。

`REBUILD_COMPLEX` 使用 `EditPlan`：输入上一轮 DSL/Plan 摘要，输出 `ADD`、`REPLACE`、`REMOVE`、`CHANGE_OPERATOR` 或 `RESET`。它必须指明受影响 condition，未受影响条件保持原样；仅新增或替换的 condition 进入召回。

## 条件召回与候选契约

每一个 Plan 叶子按 `metric`、`scope`、`entity` 分路召回，输出 `byCondition` 候选池及全局 `contractIndex`。候选须携带 metric ID、支持的操作符、参数契约、单位、排序能力和实体绑定角色。评估记录每个 condition 的 lexical/vector/rerank Top-K。

## 路由与裁决

候选池建立后确定性路由：唯一且可机械构建的条件进入 Direct；单 Group、浅 AND 树且候选无歧义进入 Simple；多 Group、OR/NOT、深树、歧义或复杂 EditPlan 进入 Full。

Simple 和 Full 都只允许 LLM 输出 `ConditionBinding` 与 `SortBinding`：`candidateKey` 必须来自对应 condition 的候选，参数必须符合 contract。二者共用同一模型配置，差异只在上下文大小与候选数量。

## 构建、校验与澄清

DSL Builder 以 Plan 逻辑树和 Binding 为唯一输入，确定性生成 DSL。Validator 校验指标 ID、参数、操作符、排序、limit、时间范围、单位和存储口径；仅执行不改变意图的修复（例如最大回撤负值转换）。失败以带 conditionId 的 clarification 返回。

## 迁移要求

1. 移除旧 `RecallPlan` fallback 作为生产路径。
2. 生产运行时完整注入 SemanticPlan、条件召回和候选裁决能力。
3. 保持 HTTP 与 WebSocket 经过同一个 LangGraph 编译入口。
4. 每一轮保存 Plan、候选池、裁决、DSL 与 clarification，支持评估闭环和增量编辑。

## 验收

- 旁路请求不调用 Plan LLM。
- 非旁路请求只走一次 Plan LLM、一次候选受限裁决。
- `REBUILD_COMPLEX` 不重写未受影响条件。
- 每个条件可产出 Top-1、Top-3 与失败归因。
- Validator 不允许不可执行 DSL 到达数据引擎。
