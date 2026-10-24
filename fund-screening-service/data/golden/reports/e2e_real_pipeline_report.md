# 基金筛选真实 Production Pipeline 全流程端到端评测报告

- **评测时间**: 2026-09-30 04:56:56 UTC
- **评测引擎**: 生产级 LangGraph 编译流 (`build_production_compiler`)
- **底层支撑**: PostgreSQL 16 pgvector (1024 维) + Ollama `qwen3-embedding` + 本地 `codex-bridge` (agy-cli / gemini-3.8-flash-low)

## 1. 全流程汇总指标

- **评测样本总量**: 5
- **端到端完全通过 (Passed)**: 0 / 5 (**0.0%**)
- **预期形态吻合率 (Outcome Match)**: 60.0%
- **端到端平均执行耗时**: 72.29s

## 2. 核心案例执行明细表

| Case ID | 自然语言 Query | 期望形态 | 实际形态 | 命中路由 | 耗时 (s) | 评测结论 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `scenario-vague-return-clarification` | 收益好的基金 | `CLARIFICATION` | `DSL` | `simple` | 73.68s | **FAILED** |
| `scenario-vague-risk-clarification` | 风险低的固收基金 | `CLARIFICATION` | `DSL` | `simple` | 71.30s | **FAILED** |
| `scenario-multi-condition-and` | 规模大于10亿且成立年限大于3年，按近1年收益降序取前20只 | `DSL` | `DSL` | `simple` | 72.98s | **FAILED** |
| `scenario-negative-max-drawdown` | 最大回撤小于15% | `DSL` | `DSL` | `simple` | 64.08s | **FAILED** |
| `scenario-date-interval-return` | 2024年以来区间收益率大于10% | `DSL` | `DSL` | `simple` | 79.40s | **FAILED** |

## 3. 详细生成结果与语法树剖析

### 案例: `scenario-vague-return-clarification`
- **用户 Query**: `收益好的基金`
- **执行路由**: `simple` | **耗时**: 73.68s
- **评测状态**: FAILED
- **未通过原因**: Generated DSL instead of Clarification
- **生成的真实 DSL**:
```json
{
  "version": "fund-screening/v1",
  "operator": "AND",
  "children": [
    {
      "sector": {
        "operator": "OR",
        "children": [
          {
            "sectorId": "1000019220000000",
            "match": "SELF_OR_DESCENDANT"
          }
        ]
      },
      "metric": {
        "operator": "AND",
        "params": [],
        "children": [
          {
            "operator": ">",
            "metric": "f_info_existingyear",
            "value": 0,
            "unit": "年",
            "params": [
              {
                "key": "endDate",
                "value": "LATEST_TRADE_DATE"
              }
            ]
          }
        ]
      },
      "sort": []
    }
  ],
  "sort": [],
  "limit": 20
}
```

### 案例: `scenario-vague-risk-clarification`
- **用户 Query**: `风险低的固收基金`
- **执行路由**: `simple` | **耗时**: 71.30s
- **评测状态**: FAILED
- **未通过原因**: Generated DSL instead of Clarification
- **生成的真实 DSL**:
```json
{
  "version": "fund-screening/v1",
  "operator": "AND",
  "children": [
    {
      "sector": {
        "operator": "OR",
        "children": [
          {
            "sectorId": "1000019220000000",
            "match": "SELF_OR_DESCENDANT"
          }
        ]
      },
      "metric": {
        "operator": "AND",
        "params": [],
        "children": [
          {
            "operator": ">",
            "metric": "f_info_existingyear",
            "value": 0,
            "unit": "年",
            "params": [
              {
                "key": "endDate",
                "value": "LATEST_TRADE_DATE"
              }
            ]
          }
        ]
      },
      "sort": []
    }
  ],
  "sort": [],
  "limit": 20
}
```

### 案例: `scenario-multi-condition-and`
- **用户 Query**: `规模大于10亿且成立年限大于3年，按近1年收益降序取前20只`
- **执行路由**: `simple` | **耗时**: 72.98s
- **评测状态**: FAILED
- **未通过原因**: Missing DSL output
- **生成的真实 DSL**:
```json
{
  "version": "fund-screening/v1",
  "operator": "AND",
  "children": [
    {
      "sector": {
        "operator": "OR",
        "children": [
          {
            "sectorId": "1000019220000000",
            "match": "SELF_OR_DESCENDANT"
          }
        ]
      },
      "metric": {
        "operator": "AND",
        "params": [],
        "children": [
          {
            "operator": ">",
            "metric": "f_info_existingyear",
            "value": 0,
            "unit": "年",
            "params": [
              {
                "key": "endDate",
                "value": "LATEST_TRADE_DATE"
              }
            ]
          }
        ]
      },
      "sort": []
    }
  ],
  "sort": [],
  "limit": 20
}
```

### 案例: `scenario-negative-max-drawdown`
- **用户 Query**: `最大回撤小于15%`
- **执行路由**: `simple` | **耗时**: 64.08s
- **评测状态**: FAILED
- **未通过原因**: Missing DSL output
- **生成的真实 DSL**:
```json
{
  "version": "fund-screening/v1",
  "operator": "AND",
  "children": [
    {
      "sector": {
        "operator": "OR",
        "children": [
          {
            "sectorId": "1000019220000000",
            "match": "SELF_OR_DESCENDANT"
          }
        ]
      },
      "metric": {
        "operator": "AND",
        "params": [],
        "children": [
          {
            "operator": ">",
            "metric": "f_info_existingyear",
            "value": 0,
            "unit": "年",
            "params": [
              {
                "key": "endDate",
                "value": "LATEST_TRADE_DATE"
              }
            ]
          }
        ]
      },
      "sort": []
    }
  ],
  "sort": [],
  "limit": 20
}
```

### 案例: `scenario-date-interval-return`
- **用户 Query**: `2024年以来区间收益率大于10%`
- **执行路由**: `simple` | **耗时**: 79.40s
- **评测状态**: FAILED
- **未通过原因**: Missing DSL output
- **生成的真实 DSL**:
```json
{
  "version": "fund-screening/v1",
  "operator": "AND",
  "children": [
    {
      "sector": {
        "operator": "OR",
        "children": [
          {
            "sectorId": "1000019220000000",
            "match": "SELF_OR_DESCENDANT"
          }
        ]
      },
      "metric": {
        "operator": "AND",
        "params": [],
        "children": [
          {
            "operator": ">",
            "metric": "f_info_existingyear",
            "value": 0,
            "unit": "年",
            "params": [
              {
                "key": "endDate",
                "value": "LATEST_TRADE_DATE"
              }
            ]
          }
        ]
      },
      "sort": []
    }
  ],
  "sort": [],
  "limit": 20
}
```
