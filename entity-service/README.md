# FinancialCopilot Entity Service

基于实体目录、精确别名匹配、pgvector 向量召回和受限 LLM 重排的金融实体识别服务。服务只返回已导入 `entity.json` 的实体，不允许大模型创建目录外实体。

## 识别流程

```text
自然语言
  -> Unicode 标准化（保留原文偏移）
  -> 精确名称/代码/别名匹配
  -> 未覆盖片段 Embedding + pgvector Top-K
  -> 上下文语义重排
  -> resolved 或 ambiguous 标准结构
```

同一片段可以命中不同类别。例如 `000001` 可能同时返回基金代码、股票代码和指数代码；上下文不足时结果为 `ambiguous`，候选按相关度排序交给下游处理。

## 环境要求

- Python 3.11+
- PostgreSQL 16 + pgvector
- Ollama `qwen3-embedding:0.6b`（1024 维）
- OpenAI 兼容的聊天模型接口（默认 DeepSeek）

## 本地启动

```powershell
cd entity-service
Copy-Item .env.example .env
python -m pip install -e ".[dev]"
python -m app.main
```

首次使用或现有 PostgreSQL 数据卷未自动执行新脚本时，手动加载表结构：

```powershell
Get-Content ..\docker\postgres\initdb\05_entity_schema.sql -Raw |
  docker exec -i docker_postgres_16 psql -U postgres -d financial_copilot
```

`.env` 中至少需要设置：

```dotenv
ENTITY_CATALOG_PATH=D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/lite-mysql/docs/temp/entity.json
ENTITY_ADMIN_TOKEN=replace-with-a-random-secret
LLM_API_KEY=replace-with-your-key
```

## 建立实体索引

请求重建：

```powershell
$headers = @{ Authorization = "Bearer replace-with-a-random-secret" }
$body = @{ force = $false } | ConvertTo-Json
$job = Invoke-RestMethod -Method Post -Uri http://localhost:8800/v1/entities/index/rebuild `
  -Headers $headers -ContentType application/json -Body $body
$job
```

查询任务状态：

```powershell
Invoke-RestMethod "http://localhost:8800/v1/entities/index/jobs/$($job.job_id)"
```

重建采用新版本写入和原子切换。失败的重建不会替换当前活动索引；重复导入相同 SHA-256 默认直接复用活动版本。

## 识别实体

```powershell
$body = @{
  text = "帮我比较华夏成长和000001最近的表现"
  options = @{ top_k = 20; include_unresolved = $true }
} | ConvertTo-Json -Depth 4

Invoke-RestMethod -Method Post -Uri http://localhost:8800/v1/entities/recognize `
  -ContentType application/json -Body $body
```

歧义结果示例：

```json
{
  "mention": "000001",
  "span": {"start": 9, "end": 15},
  "resolution": "ambiguous",
  "ambiguity": {
    "reason": "多个目录实体得分接近，当前上下文不足以唯一确定",
    "context_used": "帮我比较华夏成长和000001最近的表现",
    "context_needed": ["需要补充信息以区分：fund_code、stock_code、track_index_code"]
  },
  "candidates": [
    {
      "rank": 1,
      "entity": {
        "id": "fund_code:000001.OF",
        "category": "fund_code",
        "canonical_value": "000001.OF",
        "metadata": {"fund_name": "华夏成长"}
      },
      "match": {"method": "context_rerank", "confidence": 0.91}
    }
  ]
}
```

完整响应 JSON Schema：`GET /v1/schema/recognition-response`。交互文档：`GET /docs`。

## 主要 API

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v1/entities/recognize` | 识别自然语言中的目录实体 |
| GET | `/v1/entities/{entity_id}` | 查询活动目录中的实体 |
| POST | `/v1/entities/index/rebuild` | 异步重建目录索引（需管理员 Token） |
| GET | `/v1/entities/index/jobs/{job_id}` | 查询重建进度 |
| GET | `/v1/schema/recognition-response` | 获取响应 JSON Schema |
| GET | `/health` | 查看活动索引状态 |

当 Embedding 或 LLM 暂时不可用时，精确匹配仍可返回结果，响应 `status` 为 `degraded`，具体依赖写入 `diagnostics.degraded_dependencies`。

## Docker

先在仓库根目录的 `.env` 中设置宿主机实体文件路径和 Token：

```dotenv
ENTITY_CATALOG_PATH_HOST=D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/.worktrees/lite-mysql/docs/temp/entity.json
ENTITY_ADMIN_TOKEN=replace-with-a-random-secret
DEEPSEEK_API_KEY=replace-with-your-key
```

启动：

```powershell
docker compose -f docker/postgres/docker-compose.yml up -d postgres entity-service
```

## 测试

```powershell
cd entity-service
python -m pytest tests/unit tests/api -v
python -m pytest tests/integration -v -m integration
```

仓库测试使用小型目录 fixture，不依赖真实 LLM 或 Embedding 服务。pgvector 仓库测试使用 Docker Testcontainers。
