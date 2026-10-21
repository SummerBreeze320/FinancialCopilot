# 基金筛选 pgvector 与 DSL 金测试评估报告

- **评测时间**: 2026-09-30T04:40:09.746103+00:00
- **样本版本**: v1.0
- **总用例数**: 359
- **通过用例**: 37
- **失败用例**: 322
- **总体通过率**: 10.3%

## 1. 多通道检索指标 (Condition-Level IR)

| 通道 | Hit@1 | Hit@3 | Hit@5 | Hit@10 | MRR | nDCG@3 | Precision@3 | Recall@3 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `metric_pgvector` | 24.0% | 24.0% | 24.0% | 24.0% | 0.240 | 0.140 | 0.150 | 17.7% |
| `entity_lexical` | 2.8% | 2.8% | 2.8% | 2.8% | 0.028 | 0.021 | 0.009 | 1.0% |
| `merged` | 24.0% | 24.0% | 24.0% | 24.0% | 0.240 | 0.171 | 0.179 | 20.6% |
| `pgvector` | 24.0% | 24.0% | 24.0% | 24.0% | 0.240 | 0.160 | 0.170 | 19.6% |
| `lexical` | 2.8% | 2.8% | 2.8% | 2.8% | 0.028 | 0.021 | 0.009 | 1.0% |
| `scope_pgvector` | 5.8% | 5.8% | 5.8% | 5.8% | 0.058 | 0.039 | 0.021 | 2.0% |

## 2. 端到端 DSL 与澄清指标 (E2E Metrics)

| 指标项 | 达成数值 |
| :--- | :--- |
| exact_match_rate | 0.0% |
| structural_match_rate | 10.7% |
| matched_rate | 0.0% |
| code_matched_rate | 0.0% |

## 3. 失败根因归因分布 (7-Class Failure Attribution)

| 归因类别 | 出现频次 | 占比 | 治理动作重点 |
| :--- | :--- | :--- | :--- |
| `alias_missing` | 257 | 79.8% | 补充指标/板块/实体同义别名表 |
| `plan_error` | 40 | 12.4% | 优化 SemanticPlan 解析 Prompt 与少样本示例 |
| `vector_recall_weak` | 14 | 4.3% | 调整向量相似度阈值或增加 Top-K 深度 |
| `validator_error` | 11 | 3.4% | 排查 AST 语法树生成与语义校验器拦截规则 |

## 4. 可执行数据治理与模型调优待办清单 (Actionable Backlog)

| 案例ID | 归因类别 | 问句 (Query) | 推荐整改动作 (Remediation) |
| :--- | :--- | :--- | :--- |
| `composite-metric-and-01` | `plan_error` | 筛选基金规模大于10亿且成立年限大于3年的基金，按近3年回报降序取前10只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-04` | `plan_error` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前10只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-05` | `plan_error` | 筛选基金规模大于10亿且成立年限大于3年的基金，按近3年回报降序取前20只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-08` | `plan_error` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前20只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-09` | `plan_error` | 筛选基金规模大于10亿且成立年限大于3年的基金，按近3年回报降序取前30只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-12` | `plan_error` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前30只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-13` | `plan_error` | 筛选基金规模大于10亿且成立年限大于3年的基金，按近3年回报降序取前10只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-16` | `plan_error` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前10只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-17` | `plan_error` | 筛选基金规模大于10亿且成立年限大于3年的基金，按近3年回报降序取前20只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-metric-and-20` | `plan_error` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前20只 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-01` | `plan_error` | 筛选股票型基金中基金规模大于10亿且近3年回报大于20%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-02` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且最大回撤小于15%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-05` | `plan_error` | 筛选股票型基金中基金规模大于10亿且近3年回报大于20%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-06` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且最大回撤小于15%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-09` | `plan_error` | 筛选股票型基金中基金规模大于10亿且近3年回报大于20%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-10` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且最大回撤小于15%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-13` | `plan_error` | 筛选股票型基金中基金规模大于10亿且近3年回报大于20%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-14` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且最大回撤小于15%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-17` | `plan_error` | 筛选股票型基金中基金规模大于10亿且近3年回报大于20%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-metric-18` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且最大回撤小于15%的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-scope-or-01` | `validator_error` | 筛选股票型基金或者偏股混合基金，并要求基金规模大于10亿，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-02` | `validator_error` | 筛选偏股混合基金或者半导体行业基金，并要求成立年限大于3年，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-03` | `validator_error` | 筛选半导体行业基金或者全市场基金，并要求近3年回报大于20%，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-04` | `validator_error` | 筛选全市场基金或者股票型基金，并要求最大回撤小于15%，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-05` | `validator_error` | 筛选股票型基金或者偏股混合基金，并要求基金规模大于10亿，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-06` | `validator_error` | 筛选偏股混合基金或者半导体行业基金，并要求成立年限大于3年，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-07` | `validator_error` | 筛选半导体行业基金或者全市场基金，并要求近3年回报大于20%，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-08` | `validator_error` | 筛选全市场基金或者股票型基金，并要求最大回撤小于15%，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-09` | `validator_error` | 筛选股票型基金或者偏股混合基金，并要求基金规模大于10亿，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-scope-or-10` | `validator_error` | 筛选偏股混合基金或者半导体行业基金，并要求成立年限大于3年，排除规模过小的基金 | 检查并修正 AST 语法树生成逻辑或调整语义验证器校验边界 |
| `composite-entity-metric-01` | `plan_error` | 在招商中证白酒指数相关基金中，筛选基金规模大于10亿，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-02` | `plan_error` | 在张坤管理的基金相关基金中，筛选成立年限大于3年，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-03` | `plan_error` | 在招商中证白酒指数相关基金中，筛选近3年回报大于20%，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-04` | `plan_error` | 在张坤管理的基金相关基金中，筛选最大回撤小于15%，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-05` | `plan_error` | 在招商中证白酒指数相关基金中，筛选基金规模大于10亿，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-06` | `plan_error` | 在张坤管理的基金相关基金中，筛选成立年限大于3年，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-07` | `plan_error` | 在招商中证白酒指数相关基金中，筛选近3年回报大于20%，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-08` | `plan_error` | 在张坤管理的基金相关基金中，筛选最大回撤小于15%，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-09` | `plan_error` | 在招商中证白酒指数相关基金中，筛选基金规模大于10亿，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-entity-metric-10` | `plan_error` | 在张坤管理的基金相关基金中，筛选成立年限大于3年，按近3年回报降序 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-01` | `plan_error` | 筛选股票型基金中基金规模大于10亿且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-02` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-03` | `plan_error` | 筛选半导体行业基金中近3年回报大于20%且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-04` | `plan_error` | 筛选全市场基金中最大回撤小于15%且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-05` | `plan_error` | 筛选股票型基金中基金规模大于10亿且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-06` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-07` | `plan_error` | 筛选半导体行业基金中近3年回报大于20%且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-08` | `plan_error` | 筛选全市场基金中最大回撤小于15%且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-09` | `plan_error` | 筛选股票型基金中基金规模大于10亿且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |
| `composite-partial-clarification-10` | `plan_error` | 筛选偏股混合基金中成立年限大于3年且收益好的基金 | 优化 SemanticPlan 解析 Prompt 或扩充复合/多条件抽取的 Few-Shot 示例 |