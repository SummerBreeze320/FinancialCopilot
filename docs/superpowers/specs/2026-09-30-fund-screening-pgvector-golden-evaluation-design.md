# 基金筛选 pgvector 与 DSL 金测试闭环设计

## 目标

面向基金研究分析师与投研用户，建立可版本化的真实问句金集，同时评估 condition 级 pgvector/lexical 召回和端到端 DSL/clarification 输出。金集用于发现 aliases、embedding 文本、向量召回、裁决与 DSL 组装问题，并将修复后的结果持续回归。

## 金集组织

正式样本按维护边界拆分为 `metrics.jsonl`、`sectors-top100.jsonl`、`entities-top100.jsonl`、`research-scenarios.jsonl` 和 `regressions.jsonl`；评估器加载后视为同一个案例集合。指标集覆盖所有启用指标，板块与实体集分别覆盖业务认可的热门 100，场景集覆盖组合逻辑与多轮编辑，回归集只保存脱敏审核后的真实失败问句。

每条案例包含：

```json
{
  "id": "metric-return-1y-positive",
  "category": "metric",
  "query": "筛选近一年收益超过10%的基金",
  "difficulty": "clear",
  "expectedOutcome": "DSL",
  "expectedConditions": [
    {
      "mention": "近一年收益超过10%",
      "domain": "metric",
      "relevantCandidateKeys": ["f_nav_return_1y"],
      "primaryCandidateKey": "f_nav_return_1y"
    }
  ],
  "expectedDsl": {}
}
```

模糊指标问句以 `expectedOutcome=CLARIFICATION` 标注；例如“收益好的”“风险低的”必须返回定位到 metric condition 的结构化澄清，不允许隐式选择收益率、回撤、波动率或夏普指标。

## 覆盖矩阵

场景覆盖清晰、模糊、歧义、多组 AND/OR/NOT、排序/数量、日期区间、负值最大回撤、参数枚举、实体绑定及多轮编辑。自动生成器只生成全量启用指标、热门板块/实体的样本骨架和投研式问句候选；期望 candidate、DSL 与 clarification 必须经人工审核后标记为 gold。

## 评估执行

每条样本执行：

```text
query → semantic plan conditions → channel Top-K → merged candidate pool
      → contract index → adjudication → DSL / clarification
```

记录每个 condition 在不同通道的候选 key、分数、排名与耗时：entity lexical、metric pgvector、scope pgvector、合并候选池，以及未来可加入的 BM25/rerank 通道。

## 指标

以 `relevantCandidateKeys` 作为相关集合，按 condition、domain、channel 和全局计算：

- `Precision@K = TopK 中相关候选数 / K`；
- `Recall@K = TopK 中相关候选数 / 全部相关候选数`；
- `Hit@1/3/10`；
- `MRR`，衡量首个相关候选的排序；
- `nDCG@K`，使用 `primaryCandidateKey` 作为高相关度，其他相关 key 作为次相关度。

端到端还统计 DSL 完全匹配、结构匹配、condition binding 匹配、参数/单位/排序/limit/时间区间差异，以及 clarification 命中率。

## 失败归因与待办

报告按 `alias_missing`、`embedding_text_insufficient`、`vector_recall_weak`、`rerank_issue`、`plan_error`、`adjudication_error`、`validator_error` 分类。每个失败保留 query、condition、期望集合、各通道 Top-K 与最终输出，并生成可执行 backlog：补 aliases、扩充 embedding 文本、调整 pgvector 检索、引入/调整 rerank 或修正 Plan/Builder/Validator。

## 产物与安全

每次评估生成机器可读 JSON 和人可读 Markdown 报告，保留金集版本、目录版本、embedding 模型、运行参数和时间。`regressions.jsonl` 写入前必须去除用户标识、账户信息、会话 ID 与其他敏感内容。

## 验收

- 每个启用指标至少一个审核通过的客户式问句。
- 热门 100 板块和热门 100 实体均有固定金例。
- 每类客户场景至少包含清晰、模糊/澄清、歧义和多组条件样本。
- 报告同时给出各通道及合并池的 P@K、R@K、Hit@K、MRR、nDCG 和端到端 DSL 指标。
- 每一个失败都有可审计归因和可执行改造建议。
