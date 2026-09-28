# Local LLM CLI Bridge

把本机已经登录的 Agy CLI 或 Codex CLI 暴露为最小 OpenAI-compatible Chat Completions API，供基金筛选服务调用。默认使用 Agy，Codex 仅作为显式配置的备用后端，不做自动降级。

```powershell
cd codex-bridge
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

验证：

```powershell
Invoke-RestMethod http://127.0.0.1:8000/health
```

默认一次只运行一个 Agy 请求，并使用 sandbox、禁用 slash command expansion。Agy structured output 直接使用上游传入的 JSON Schema。配置前缀保持为 `CODEX_BRIDGE_`：

- `CODEX_BRIDGE_BACKEND=agy`，可选 `agy` 或 `codex`
- `CODEX_BRIDGE_AGY_COMMAND=agy`
- `CODEX_BRIDGE_CODEX_COMMAND=codex`
- `CODEX_BRIDGE_TIMEOUT_SECONDS=180`
- `CODEX_BRIDGE_MAX_CONCURRENCY=1`

启动 Agy 后端：

```powershell
$env:CODEX_BRIDGE_BACKEND = "agy"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

切换到 Codex 后端：

```powershell
$env:CODEX_BRIDGE_BACKEND = "codex"
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

基金筛选服务默认使用：

```text
FUND_SCREENING_LLM_BASE_URL=http://127.0.0.1:8000/v1
FUND_SCREENING_LLM_API_KEY=local-agy
FUND_SCREENING_LLM_MODEL=gemini-3.8-flash-low
FUND_SCREENING_LLM_REASONING_EFFORT=low
```
