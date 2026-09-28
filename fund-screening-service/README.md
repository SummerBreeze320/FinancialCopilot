# Fund Screening Service

独立 FastAPI 服务，将自然语言基金筛选条件转换为 `fund-screening/v1` DSL。

```powershell
cd fund-screening-service
python -m pip install -e ".[dev]"
python -m uvicorn app.main:app --port 8810
python -m pytest -v
```

HTTP：`POST /v1/fund-screening/commands`，请求体为 `{"query":"成立年限大于10年"}`。
WebSocket：`/v1/fund-screening/ws`，使用同一请求体并依次接收五段事件及 `finalized`。

生产环境通过 `FUND_SCREENING_METRICS_CATALOG_PATH` 和 `FUND_SCREENING_SECTORS_CATALOG_PATH` 配置目录路径。指标和板块初始数据位于项目 sibling worktree 的 `docs/temp/metrics.json`、`docs/temp/sectors.json`。
