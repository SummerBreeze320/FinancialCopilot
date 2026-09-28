# Local Codex Bridge

把本机已经登录的 Codex CLI 暴露为最小 OpenAI-compatible Chat Completions API，供基金筛选服务调用。

```powershell
cd codex-bridge
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

默认一次只运行一个 Codex 请求，并使用 `read-only` sandbox 和 ephemeral session。配置前缀为 `CODEX_BRIDGE_`：

- `CODEX_BRIDGE_CODEX_COMMAND=codex`
- `CODEX_BRIDGE_TIMEOUT_SECONDS=180`
- `CODEX_BRIDGE_MAX_CONCURRENCY=1`

基金筛选服务默认使用：

```text
FUND_SCREENING_LLM_BASE_URL=http://127.0.0.1:8000/v1
FUND_SCREENING_LLM_MODEL=gpt-5.6-terra
FUND_SCREENING_LLM_REASONING_EFFORT=low
```
