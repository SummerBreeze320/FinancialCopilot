# FinancialCopilot Memory Service

基于 [Graphiti](https://github.com/getzep/graphiti) 的时序知识图谱记忆服务，为 Java 后端提供短期记忆、长期记忆和用户画像能力。

## 架构

```
Java (Spring Boot)
  └─ MemoryClient (HTTP) ──→ Python Memory Service (FastAPI)
                                ├── 短期记忆: Redis 缓存 + Graphiti Episode
                                ├── 长期记忆: Graphiti 混合检索 (向量+图+时间)
                                └── 用户画像: LLM 分类 + 时序追加
                                     ↓
                                Neo4j (知识图谱) + Ollama (Embedding)
```

## 快速启动

```bash
# 1. 复制环境配置
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY

# 2. 安装依赖
pip install -e .

# 3. 启动
python -m app.main
# 或
uvicorn app.main:app --host 0.0.0.0 --port 8700 --reload
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/sessions/{sid}/messages` | 写入对话消息 (短期+Episode) |
| GET | `/v1/sessions/{sid}/context` | 读取短期记忆上下文 |
| DELETE | `/v1/sessions/{sid}` | 清空会话短期记忆 |
| POST | `/v1/memory/search` | 混合检索长期记忆 |
| POST | `/v1/memory/search/temporal` | 时点回溯查询 |
| POST | `/v1/memory/forget` | 遗忘指定记忆 |
| GET | `/v1/users/{uid}/profile` | 获取用户画像 |
| POST | `/v1/users/{uid}/profile/extract` | 从对话提取画像 |
| GET | `/v1/users/{uid}/profile/diff` | 画像变化历史 |

## Docker

```bash
cd docker/postgres
docker-compose up -d memory-service
```

## 依赖

- Python >= 3.11
- Neo4j 5.26+ (已有)
- Redis (已有)
- Ollama + qwen3-embedding:0.6b (已有)
- DeepSeek API (已有)
