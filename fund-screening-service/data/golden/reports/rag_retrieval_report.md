# 基金筛选 RAG 真实 pgvector 向量召回评测报告

- **评测生成时间**: 2026-09-30 04:39:37 UTC
- **向量数据库**: PostgreSQL 16 + pgvector (1024 维 HNSW 余弦索引)
- **向量模型**: Ollama `qwen3-embedding:0.6b` (1024 维)

## 1. 核心指标汇总表

| 评测领域 | 样本总量 | Hit@1 | Hit@3 | Hit@5 | Hit@10 | MRR | NDCG@10 | 平均耗时 (ms) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **指标体系 (Metrics)** | 168 | 69.6% | 87.5% | 94.6% | 97.6% | 0.800 | 0.843 | 44.9ms |
| **板块体系 (Sectors)** | 100 | 71.0% | 84.0% | 90.0% | 96.0% | 0.795 | 0.835 | 52.4ms |
| **复合研究场景 (Composite)** | 80 | 21.2% | 47.5% | 62.5% | 75.0% | 0.381 | 0.345 | 47.6ms |

## 2. 评测分领域详细表现

### 2.指标体系 (Metrics) 召回分析
- **样本数**: 168
- **平均延迟**: 44.89ms (P95: 64.15ms)
- **Hit@1 (Top-1 命中率)**: 69.64%
- **Hit@3 (Top-3 命中率)**: 87.50%
- **Hit@5 (Top-5 命中率)**: 94.64%
- **Hit@10 (Top-10 召回率)**: 97.62%
- **MRR**: 0.7998
- **NDCG@10**: 0.8434
- **未命中数量 (Bad Cases)**: 4

#### 未命中典型案例 (Bad Cases)
| Case ID | Query | 期望指标/板块 | 实际 Top-3 召回结果 |
| :--- | :--- | :--- | :--- |
| `metric-f_return_q` | 筛选季度收益大于10%的基金 | `f_return_q` | 近10年回报(f_return_10y:0.68), 年化收益率(f_risk_returnyearly:0.66), 今年以来回报(f_return_ytd:0.65) |
| `metric-f_nav_periodreturnranking_2y` | 筛选近两年收益排名大于10%的基金 | `f_nav_periodreturnranking_2y` | 近10年回报(f_return_10y:0.62), 今年以来回报(f_return_ytd:0.62), 近1年回报(f_return_1y:0.60) |
| `metric-f_nav_periodreturnranking_3y` | 筛选近三年收益排名大于10%的基金 | `f_nav_periodreturnranking_3y` | 近10年回报(f_return_10y:0.66), 今年以来回报(f_return_ytd:0.65), 近3年回报(f_return_3y:0.64) |
| `metric-f_netasset_total_cc` | 筛选合并规模大于10亿的基金 | `f_netasset_total_cc` | 规模同类排名(f_info_scaleranking:0.52), 同类基金平均规模(f_info_avgfundscale:0.51), 基金规模合计(f_netasset_total2:0.49) |

### 2.板块体系 (Sectors) 召回分析
- **样本数**: 100
- **平均延迟**: 52.42ms (P95: 64.46ms)
- **Hit@1 (Top-1 命中率)**: 71.00%
- **Hit@3 (Top-3 命中率)**: 84.00%
- **Hit@5 (Top-5 命中率)**: 90.00%
- **Hit@10 (Top-10 召回率)**: 96.00%
- **MRR**: 0.7949
- **NDCG@10**: 0.8347
- **未命中数量 (Bad Cases)**: 4

#### 未命中典型案例 (Bad Cases)
| Case ID | Query | 期望指标/板块 | 实际 Top-3 召回结果 |
| :--- | :--- | :--- | :--- |
| `sector-1000002540000000` | 筛选ETF | `1000002540000000` | ETF(场内，除货币型)(1000032623000000:0.59), ETF基金[行情](a201010l00000000:0.57), ETF基金(含未成立、已到期)(1000051239000000:0.57) |
| `sector-1000006334000000` | 筛选混合型基金 | `1000006334000000` | 混合型基金(2001010200000000:0.72), 混合型[SH](1000033850000000:0.71), 灵活策略混合型[HT](1000031892000000:0.70) |
| `sector-1000007764000000` | 筛选能源主题基金 | `1000007764000000` | Wind能源互联网概念主题基金(1000022627000000:0.69), Wind垃圾发电概念主题基金(1000022505000000:0.67), 电力行业主题基金[Wind](1000022715000000:0.67) |
| `sector-1000009160000000` | 筛选上市ETF | `1000009160000000` | 股票ETF(上交所)(1000071851000000:0.65), 港股ETF[HT](1000033771000000:0.64), 科创100ETF(RAGCUSTOM0001:0.64) |

### 2.复合研究场景 (Composite) 召回分析
- **样本数**: 80
- **平均延迟**: 47.61ms (P95: 65.87ms)
- **Hit@1 (Top-1 命中率)**: 21.25%
- **Hit@3 (Top-3 命中率)**: 47.50%
- **Hit@5 (Top-5 命中率)**: 62.50%
- **Hit@10 (Top-10 召回率)**: 75.00%
- **MRR**: 0.3806
- **NDCG@10**: 0.3445
- **未命中数量 (Bad Cases)**: 20

#### 未命中典型案例 (Bad Cases)
| Case ID | Query | 期望指标/板块 | 实际 Top-3 召回结果 |
| :--- | :--- | :--- | :--- |
| `composite-metric-and-04` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前10只 | `f_risk_maxdownside,f_info_fundscale,f_return_3y` | 规模同类排名(f_info_scaleranking:0.67), 最大回撤同类排名(f_risk_maxdownsideranking:0.60), 近1年回报排名(f_nav_periodreturnranking_1y:0.59) |
| `composite-metric-and-08` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前20只 | `f_risk_maxdownside,f_info_fundscale,f_return_3y` | 规模同类排名(f_info_scaleranking:0.67), 最大回撤同类排名(f_risk_maxdownsideranking:0.60), 单年度回报排名(f_nav_periodreturnranking_y:0.58) |
| `composite-metric-and-12` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前30只 | `f_risk_maxdownside,f_info_fundscale,f_return_3y` | 规模同类排名(f_info_scaleranking:0.66), 近3年回报排名(f_nav_periodreturnranking_3y:0.60), 最大回撤同类排名(f_risk_maxdownsideranking:0.58) |
| `composite-metric-and-16` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前10只 | `f_risk_maxdownside,f_info_fundscale,f_return_3y` | 规模同类排名(f_info_scaleranking:0.67), 最大回撤同类排名(f_risk_maxdownsideranking:0.60), 近1年回报排名(f_nav_periodreturnranking_1y:0.59) |
| `composite-metric-and-20` | 筛选最大回撤小于15%且基金规模大于10亿的基金，按近3年回报降序取前20只 | `f_risk_maxdownside,f_info_fundscale,f_return_3y` | 规模同类排名(f_info_scaleranking:0.67), 最大回撤同类排名(f_risk_maxdownsideranking:0.60), 单年度回报排名(f_nav_periodreturnranking_y:0.58) |
| `composite-scope-or-01` | 筛选股票型基金或者偏股混合基金，并要求基金规模大于10亿，排除规模过小的基金 | `f_info_fundscale` | 规模同类排名(f_info_scaleranking:0.64), 投资类型(二级分类)(f_info_investtype:0.61), 同类基金平均规模(f_info_avgfundscale:0.61) |
| `composite-scope-or-05` | 筛选股票型基金或者偏股混合基金，并要求基金规模大于10亿，排除规模过小的基金 | `f_info_fundscale` | 规模同类排名(f_info_scaleranking:0.64), 投资类型(二级分类)(f_info_investtype:0.61), 同类基金平均规模(f_info_avgfundscale:0.61) |
| `composite-scope-or-09` | 筛选股票型基金或者偏股混合基金，并要求基金规模大于10亿，排除规模过小的基金 | `f_info_fundscale` | 规模同类排名(f_info_scaleranking:0.64), 投资类型(二级分类)(f_info_investtype:0.61), 同类基金平均规模(f_info_avgfundscale:0.61) |
| `composite-entity-metric-01` | 在招商中证白酒指数相关基金中，筛选基金规模大于10亿，按近3年回报降序 | `f_info_fundscale,f_return_3y` | 规模同类排名(f_info_scaleranking:0.58), 市值属性(f_style_marketvalueattribute:0.56), 风格属性(f_style_styleattribute:0.55) |
| `composite-entity-metric-04` | 在张坤管理的基金相关基金中，筛选最大回撤小于15%，按近3年回报降序 | `f_risk_maxdownside,f_return_3y` | 规模同类排名(f_info_scaleranking:0.61), 最大回撤同类排名(f_risk_maxdownsideranking:0.59), 销售地区(f_info_distributionarea:0.59) |
