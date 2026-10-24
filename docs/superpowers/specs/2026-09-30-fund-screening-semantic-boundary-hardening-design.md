# 基金筛选语义链路边界收紧设计

## 目标

在既有统一语义编译主链上收紧职责边界：意图识别只决定会话动作，语义计划只定义逻辑结构，候选裁决只解析叶子，Builder 只确定性构建 DSL，Validator 只安全修复或拒绝。结果必须支持条件级追踪、澄清续接、稳定编辑和金标归因。

## 非目标

- 不增加第二个模型或为每个阶段单独部署模型；现有同一模型配置复用。
- 不在本次根据主观判断调整 pgvector、embedding、rerank 或别名字典；这些由金标报告驱动。
- 不改变基金数据引擎 DSL 语义与已有数据指标口径。

## 职责和调用顺序

```text
IntentFunnel -> CREATE | REBUILD
StrictDirectProbe -> 可证明的零调用短路，未命中即放行
SemanticPlan | EditPlan -> 条件树与稳定 conditionId
Condition Recall -> 每个条件独立候选池和 contract index
Route -> direct | simple | full（仅确定性判断）
Resolve -> 确定性绑定，必要时一次候选受限 LLM 裁决
Builder -> 仅按原计划树组装 DSL
Validator -> ACCEPT | REPAIRED | REJECT_OR_CLARIFY
Final DSL | ClarificationPlan
```

`CREATE/REBUILD` 不得推导 `direct/simple/full`；后者只能基于已经生成的计划树、候选歧义和参数完整性决定。`StrictDirectProbe` 可以在计划前结束，但仅允许纯基金代码、精确实体、完全匹配的指标 ID/别名且运算符和值都明确，以及无歧义的简单编辑补丁。它不得解释模糊词、推断指标或构造逻辑树。

## 计划、解析和构建契约

`SemanticPlan` 必须携带每个叶子的稳定 `conditionId`、mention、domain、operator/value/unit/period hint、group 和树逻辑。它是 AND/OR/NOT 的唯一来源。`EditPlan` 以当前 DSL 派生的条件树快照为输入，所有修改引用稳定 id；操作覆盖 `ADD`、`REMOVE`、`REPLACE`、`CHANGE_OPERATOR`、`CHANGE_VALUE` 和 `RESET`。未受影响叶子保持不变。

`ResolvedCondition`（现有 ConditionBinding 的收紧版本）仅允许输出：`conditionId`、`selectedCandidateId`、`bindingRole`、`operator`、`value`、`unit`、`period`、`params` 与 `confidence`。其 candidate id 必须来自该条件候选池，且不得新增、删除、移动或重组任何计划节点。

Builder 接收 `SemanticPlan/EditPlan`、已解析叶子和排序绑定，按原始树递归生成 DSL；不得从 query 或 LLM 自行生成逻辑结构。

## 条件级候选契约和 Trace

每个候选条目在 condition 视图中均需有以下字段：

```text
condition_id, candidate_type, canonical_id, display_name,
aliases_matched, retrieval_channel, retrieval_score, rank,
parameter_schema, storage_convention
```

全局 contract index 仍以 `canonical_id` 去重保存指标能力、支持操作符、单位、参数和存储口径；condition candidate view 保存“为什么被召回”。Trace 必须记录：计划树、每条件按通道 Top-K、合并池、路由原因、确定性/LLM 裁决、构建 DSL、校验状态和修复前后差异。禁止在 trace 中记录用户标识或密钥。

## 分流和模型预算

路由规则必须是确定性的：存在 OR/NOT、多业务组、树深超过阈值、条件数超过阈值、复杂 EditPlan、候选 Top1/Top2 高分且间隔小、参数不完整或含阻塞歧义，进入 `full`；其余单组浅 AND 可进入 `simple`；全部叶子已由精确候选和机械参数解析时进入 `direct`。

- strict direct：0 次模型调用；
- simple：1 次 SemanticPlan；仅当无法确定性绑定时才增加 1 次候选受限裁决；
- full：1 次 SemanticPlan（或复杂 EditPlan）和 1 次候选受限裁决。

模型调用次数、跳过原因和路由理由进入 trace 并可被评测统计。

## ClarificationPlan 与 Validator

澄清是正式成功结果，不是异常。`ClarificationPlan` 至少包含 `ambiguousMentions`、`candidateInterpretations`、`question`、`blockedConditionIds` 和可续接的已解析计划/绑定。系统保留所有不阻塞的明确条件，下一轮仅补齐被阻塞叶子。

Validator 的结果为：

- `ACCEPT`：DSL 原样可执行；
- `REPAIRED`：只进行不改变投资含义的规范化，例如最大回撤存储负值转换、百分比单位、日期格式；记录修复；
- `REJECT_OR_CLARIFY`：无法保证语义等价，例如不支持的操作符、冲突区间、未知指标、模糊未消解，不得输出可执行 DSL。

## 评测与验收

每个金标案例按 condition 评估 mention/category、各通道和合并池的 P@K/R@K/Hit@K/MRR/nDCG、binding 正确率、DSL 结构/参数正确率和 clarification 正确率。失败报告必须能归因至 plan、alias、embedding 文本、向量召回、rerank、adjudication、builder 或 validator。

验收条件：

1. `CREATE/REBUILD` 与 `direct/simple/full` 的决策独立且可追踪。
2. 无法证明精确性的请求不会被 strict direct 消费。
3. Builder 对相同计划和绑定产生稳定 DSL，逻辑树不受裁决模型输出影响。
4. 任何不可安全修复的 DSL 返回 ClarificationPlan，且明确条件可以在下一轮复用。
5. 编辑动作引用稳定条件 id，且复杂编辑只重新召回新增/替换叶子。
6. 测试覆盖上述边界；金标报告可定位 condition 级失败。
