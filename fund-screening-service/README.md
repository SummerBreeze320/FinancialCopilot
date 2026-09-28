# Fund Screening Service

基于 RAG 编译链路构建的基金筛选 Agent，将用户的自然语言筛选需求转换为数据引擎可解析的 `fund-screening/v1` Command。系统把一次不可控的 LLM 生成拆为五段可观测流程：请求归一化、语义拆解与 metrics/scope/entity 三路召回、候选绑定与命令构建、合法性校验和自动修复、最终 DSL 收口。

Query 可同时包含指标及其范围/时间参数、基金板块、公司/经理/指数等实体、排序、数量和多轮修改指令。调用方在追问时完整传回上一轮 DSL；简单的删除、limit、排序修改走确定性 AST 编辑，新增/替换指标或复杂逻辑重新进入召回和裁决链路。

## MVP 验收范围

首个最小用例验证：主动权益板块（股票型开放式基金 OR 混合型开放式基金）、最大回撤低于 15%、规模大于 10 亿、按近三年收益降序取前 20 只。Validator 会将“回撤低于 15%”转换为底层负值口径 `f_risk_maxdownside >= -15`。

```powershell
cd fund-screening-service
python -m pip install -e ".[dev]"
python -m uvicorn app.main:app --port 8810
python -m pytest -v
```

真实 RAG + LLM 链路需要设置：

```dotenv
FUND_SCREENING_PIPELINE_MODE=rag
FUND_SCREENING_METRICS_CATALOG_PATH=D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/lite-mysql/docs/temp/metrics.json
FUND_SCREENING_SECTORS_CATALOG_PATH=D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/lite-mysql/docs/temp/sectors.json
FUND_SCREENING_ENTITY_CATALOG_PATH=D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/lite-mysql/docs/temp/entity.json
FUND_SCREENING_LLM_BASE_URL=http://127.0.0.1:8000/v1
FUND_SCREENING_LLM_API_KEY=local-codex
FUND_SCREENING_LLM_MODEL=gpt-5.3-codex
FUND_SCREENING_LLM_REASONING_EFFORT=low
FUND_SCREENING_EMBEDDING_BASE_URL=http://127.0.0.1:11434/v1
FUND_SCREENING_EMBEDDING_MODEL=qwen3-embedding:0.6b
FUND_SCREENING_MILVUS_URI=http://127.0.0.1:19530
FUND_SCREENING_METRIC_COLLECTION=fund_metrics
FUND_SCREENING_SCOPE_COLLECTION=fund_scopes
```

目录路径齐全时，`app.main:app` 自动启用真实链路：Codex 生成 pre-recall plan → metric/scope Milvus 与 entity lexical 三路召回 → Codex 在 Candidate Pool 内裁决填参 → Command Builder → Validator/Repair → DSL。

HTTP：`POST /v1/fund-screening/commands`，请求体为 `{"query":"成立年限大于10年"}`。
WebSocket：`/v1/fund-screening/ws`，使用同一请求体并依次接收五段事件及 `finalized`。

```powershell
$body = @{
  query = "筛选最大回撤低于15%、规模大于10亿的主动权益基金，按近3年收益降序取前20只"
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8810/v1/fund-screening/commands `
  -ContentType application/json -Body $body
```

第二轮编辑把响应中的 `dsl` 完整放入 `previousDsl`：

```json
{
  "query": "改成前50只",
  "previousDsl": {"version": "fund-screening/v1"}
}
```

生产环境通过 `FUND_SCREENING_METRICS_CATALOG_PATH` 和 `FUND_SCREENING_SECTORS_CATALOG_PATH` 配置目录路径。指标和板块初始数据位于项目 sibling worktree 的 `docs/temp/metrics.json`、`docs/temp/sectors.json`。
