# 基金筛选语义编译与三级分流设计

## 1. 目标

将自然语言基金筛选请求编译为数据引擎可执行的 `fund-screening/v1` DSL，并在准确性、时延和可维护性之间取得平衡。

系统保留 Direct、Simple、Full 三级路径：

- Direct：不调用生成式 LLM，处理能够无歧义机械编译的请求。
- Simple：使用一次语义计划 LLM 和一次轻量候选裁决 LLM，处理单组、浅层逻辑请求。
- Full：使用一次语义计划 LLM 和一次完整候选裁决 LLM，处理多组、嵌套逻辑、复杂实体绑定及复杂编辑。

Semantic Plan 是业务分组和逻辑结构的唯一来源。候选裁决阶段只绑定逻辑树叶子，不得重新解释或改写逻辑树。

## 2. 总体流程

```text
QueryPreprocess
    ↓
StrictDirectProbe
    ├── DirectBuild（0 LLM）
    └── SemanticPlan（LLM #1）
            ↓
       RecallByCondition
       Metric / Scope / Entity
            ↓
       CandidateResolutionAnalysis
            ↓
       Simple / Full Router
            ├── SimpleAdjudication（轻量 LLM #2）
            └── FullAdjudication（完整 LLM #2）
            ↓
       PlanTree + ConditionBindings
            ↓
       CommandBuild
            ↓
       ValidatorAndRepair
            ↓
       CommandFinalize / Clarification
```

## 3. QueryPreprocess

QueryPreprocess 只处理确定性工作：

- 保存原始 query，并生成 normalized query。
- 归一化全角符号、金额、百分比、日期和数量表达。
- 装载 `conversationId`、`previousDsl` 和上一轮 clarification。
- 识别能够确定判断的 CREATE、EDIT、CLARIFY 意图。
- 提取明显的 limit、sort 和基金代码信号。

该阶段不负责选择指标、板块或实体，也不负责构建业务逻辑树。

## 4. Strict Direct Probe

Strict Direct Probe 位于所有生成式 LLM 调用之前，只接受能够确定性编译的请求。

允许进入 Direct 的条件：

- 只有一个业务条件，且不包含 OR、NOT、嵌套逻辑或复杂编辑。
- 名称、代码或 alias 精确匹配唯一候选。
- 实体 Binding Contract 唯一。
- 操作符、比较值和单位明确。
- 指标必填参数可由用户输入、确定性默认值或运行时绑定完整补齐。
- Candidate Contract 完整且状态允许 Direct。

典型请求包括纯基金代码、精确基金名称、精确基金经理，以及可严格解析的单指标表达式。

Direct 不以单一向量阈值作为充分条件。向量高分但缺少精确匹配、候选唯一性或参数完整性时，必须进入语义主链路。

## 5. Semantic Plan

非 Direct 请求统一调用 LLM #1，生成结构化 Semantic Plan。该步骤负责：

- mention 切分。
- Condition Group 划分。
- condition 的 metric、scope、entity 领域分类。
- Group 之间的逻辑关系。
- Group 内 metric 和 scope 的递归逻辑树。
- operator、value、unit、sort、limit 的语言提示。
- 编辑场景中的目标引用和编辑操作提示。

该步骤禁止生成 metric ID、sector ID、entity ID 或最终 DSL。

建议的核心结构：

```json
{
  "intent": "CREATE",
  "groupOperator": "AND",
  "groups": [
    {
      "groupId": "g1",
      "scopeTree": {
        "operator": "OR",
        "children": [
          {
            "conditionId": "s1",
            "mention": "主动权益基金",
            "domain": "scope"
          }
        ]
      },
      "metricTree": {
        "operator": "AND",
        "children": [
          {
            "conditionId": "m1",
            "mention": "成立超过5年",
            "domain": "metric",
            "operatorHint": ">",
            "valueHint": 5,
            "unitHint": "年"
          }
        ]
      }
    }
  ],
  "sortMentions": [],
  "limit": 20
}
```

每个叶子必须具有稳定的 `conditionId`，并保留原始 mention。推荐记录原始字符位置，用于处理重复 mention 和多轮局部修改。

## 6. Condition 级三路召回

召回器根据 Semantic Plan 中每个 condition 的 domain 机械分发：

- metric：精确名称/alias、词法检索、pgvector 检索及可选 rerank。
- scope：精确名称/alias、词法检索、pgvector 检索及可选 rerank。
- entity：代码、名称、alias 和 lexical 检索，必要时对接 NER 服务。

召回查询不只使用 mention，还应包含局部条件文本和所在 Group 的上下文，避免短词召回歧义。

候选结果必须同时提供两种索引：

```json
{
  "byCondition": {
    "m1": {
      "mention": "成立超过5年",
      "domain": "metric",
      "candidates": []
    }
  },
  "contractIndex": {
    "f_info_existingyear": {}
  }
}
```

- `byCondition` 用于限制 LLM 只能选择对应 condition 的候选。
- `contractIndex` 用于 CommandBuild 和 Validator 确定性查询元数据。

## 7. Simple / Full 分流

完成 Semantic Plan 和召回后，使用确定性规则进行第二次分流。路由依据包括：

- Group 数量。
- 逻辑树最大深度。
- OR、NOT 和跨 Group 逻辑。
- Condition 数量。
- Top1/Top2 分差和候选歧义。
- 实体 Binding Role 数量。
- 必填参数是否可补齐。
- 是否为复杂编辑。

Simple 的典型条件：

- 单 Group。
- 无 NOT。
- 只有 AND，或至多一层简单 OR。
- Condition 数量受限。
- 候选规模较小且没有严重歧义。
- 不改变上一轮 DSL 的复杂拓扑。

其他情况进入 Full。无法可靠判断时默认进入 Full。

Simple 和 Full 均总计调用两次 LLM；区别在于第二次调用的候选数量、Prompt、模型强度和允许处理的复杂度。

## 8. Candidate Adjudication

LLM #2 不输出完整 DSL，也不重建 Group 和逻辑树。它只输出 Condition Binding：

```json
{
  "conditionBindings": [
    {
      "conditionId": "m1",
      "candidateKey": "f_info_existingyear",
      "operator": ">",
      "value": 5,
      "unit": "年",
      "params": {
        "endDate": "LATEST_TRADE_DATE"
      }
    }
  ],
  "sortBindings": [],
  "clarifications": []
}
```

约束如下：

- `candidateKey` 必须来自对应 condition 的候选列表。
- operator 必须来自 Candidate Contract。
- ENUM 参数必须输出目录中的整数 value。
- 不得删除、增加或移动 Semantic Plan 中的 condition。
- 无法确定时输出结构化 clarification，禁止猜测。

Simple Adjudication 使用较小 Top-K、精简契约和轻量模型。Full Adjudication支持多 Group、嵌套逻辑、复杂 Binding Role 和复杂编辑，但仍只绑定叶子。

## 9. 实体 Binding Contract

实体契约除 `preferredMetric` 外，还应支持多种 Binding Role：

```json
{
  "entityType": "INDEX",
  "bindings": [
    {
      "role": "TRACKED_INDEX",
      "targetMetric": "f_info_trackindex",
      "operator": "="
    },
    {
      "role": "BENCHMARK",
      "targetParameter": "indexCode"
    }
  ]
}
```

这样可区分“沪深300作为跟踪指数条件”和“沪深300作为 Beta 基准参数”。LLM 选择 Binding Role，程序根据契约完成实际绑定。

## 10. CommandBuild

CommandBuild 使用 Semantic Plan 逻辑树和 Condition Bindings 确定性构建 DSL：

- 按 `conditionId` 将绑定结果挂回原逻辑树。
- 每个业务 Group 必须包含 scope；缺失时补默认板块。
- 同一指标允许因参数不同而重复出现。
- 指标参数按 Contract 中的 `position` 序列化。
- 实体根据 Binding Contract 转换为指标条件或指标参数。
- 所有业务 ID 必须存在于 `contractIndex`。

LLM 不参与最终 DSL 组装。

## 11. ValidatorAndRepair

Validator 负责：

- DSL 递归结构和 Group 规则。
- metric、scope、entity ID 合法性。
- 指标参数必填项、类型、顺序、日期范围和 ENUM 映射。
- operator、value type、unit、precision、sort 和 limit。
- 基于 `storageConvention` 的负值指标转换。
- 每个 Group 的默认 scope。

只有不会改变用户投资意图的错误允许自动修复。无法确定的问题产生结构化 clarification，并携带 `conditionId`，以便下一轮只恢复对应局部节点。

## 12. 多轮编辑

简单编辑由程序直接应用 DSL Patch：limit、sort 方向，以及具有唯一目标的条件删除。

复杂编辑由 LLM 生成结构化 Edit Plan。仅对新增或替换的 condition 执行召回和裁决，然后由程序把 Patch 应用于上一轮 Semantic Plan/DSL。禁止让 LLM 重写整份上一轮 DSL。

Checkpoint 至少保存：

- Semantic Plan。
- `byCondition` Candidate Pool。
- Condition Bindings。
- 最终 DSL。
- 未完成的 clarification。

## 13. 错误处理与降级

- Strict Direct失败时进入语义主链路，不直接返回错误。
- Semantic Plan结构校验失败时允许一次结构化重试。
- 单个 condition召回为空时返回针对该 condition 的 clarification。
- 候选裁决失败时只重试失败的 binding，不重新生成逻辑树。
- Validator只执行有界修复；超过上限后返回 clarification。
- Redis不可用时是否允许退回内存必须通过配置明确控制，并在健康检查中真实暴露。

## 14. 可观测性

每个请求记录：

- Direct 命中或拒绝原因。
- Semantic Plan 的 Group、Condition 和逻辑深度。
- 每个 condition 的召回 Top-K、分数和耗时。
- Simple/Full 路由结果及理由。
- LLM模型、耗时、Token和结构化重试次数。
- Condition Binding结果。
- Validator错误、自动修复和 clarification。

不得在生产日志中记录未脱敏的用户敏感信息。

## 15. 验收标准

至少覆盖以下场景：

1. 基金代码 Strict Direct，0次 LLM。
2. 单指标严格表达式 Direct 或安全进入 Simple。
3. 单 Group 多个 AND 条件进入 Simple。
4. `A AND (B OR C)` 保持递归逻辑结构。
5. 多 Group 且每组独立 scope。
6. 同一指标不同参数重复出现。
7. 基金公司、基金经理、股票和指数实体正确绑定。
8. 指数实体可绑定为指标条件或 `indexCode` 参数。
9. 必填参数缺失产生 condition级 clarification。
10. 最大回撤根据结果契约转换负值口径。
11. 简单编辑不调用语义 LLM。
12. 复杂编辑只召回和修改受影响的 condition。
13. HTTP和WebSocket产生相同最终 DSL。
14. PostgreSQL、Embedding、LLM和Redis真实依赖 Smoke Test通过。

## 16. 非目标

- 不让 LLM自由创造指标、板块或实体 ID。
- 不让候选裁决 LLM重新生成逻辑树。
- 不在路由器中实现完整业务语义理解。
- 不在 Validator 中维护大规模指标名称硬编码。
- 不在本阶段引入开放式 Python 策略代码执行。
