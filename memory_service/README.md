# Financial Copilot - 长期记忆系统 (Long-Term Memory Service)

独立的金融智能体长期记忆服务，负责将已准备好的会话与执行记录沉淀为高质量、强一致的长期事实与经验，并为在线大模型推理提供超低延迟、确定性预算控制的记忆检索。

---

## 一、系统核心设计原则

1. **固定输入边界**：处理已准备好的会话/工具执行记录，不与具体消息收集钩子或 DAG 节点耦合。
2. **权威与索引分层架构**：
   - **MySQL (权威存储)**：包含 8 张核心表，版本不可变追加 (`append-only`)，通过发布指针 `published_version` 原子切换生效快照，单值事实槽位通过悲观锁/CAS 保证一致性。
   - **Milvus (向量索引)**：纯只读加速与重构缓存，主键为 `memory_id:version`，可随时基于 MySQL 权威数据全量离线重建。
3. **安全准入发布门禁 (Safe Publication Gate)**：
   - 任何记忆版本必须经过规则校验 (`VALIDATED`) 并离线预编译为 `MemoryPack` 后，才允许写入索引并切换指针。
4. **在线 Reader 零生成式 LLM 调用**：
   - L0 槽位秒级直读 + L1 语义过滤 + 运行时适用边界硬过滤 (`ApplicabilityFilter`)。
   - 利用离线预计算的 Token 计数严格依预算拼装，无在线调用 Tokenizer 或大模型的额外延迟，实测平均检索延迟 **< 7ms**。

---

## 二、目录结构

```text
memory_service/
├── api/                      # 在线 FastAPI 服务与接口路由
│   ├── main.py               # 应用主入口 (/health)
│   └── routes/
│       ├── recall.py         # POST /api/v1/memory/recall (在线只读检索)
│       └── pipeline.py       # POST /api/v1/memory/process-session (离线处理)
├── domain/                   # 领域模型
│   ├── enums.py              # 枚举定义 (MemoryType, VerificationStatus等)
│   ├── fact.py               # 原子事实 (Fact) 与槽位 (FactSlot)
│   ├── procedure.py          # 过程性经验 (Procedure)
│   ├── episode.py            # 交互经历中间体 (Episode)
│   ├── evidence.py           # 支持证据 (Evidence)
│   ├── memory_pack.py        # 预编译物化包 (MemoryPack)
│   └── memory.py             # 记忆聚合根 (Memory) 与版本快照 (MemoryVersion)
├── engine/                   # 离线记忆流水线核心
│   ├── compilation/          # 预编译物化器 (MemoryCompiler)
│   ├── extraction/           # 事实提炼 (FactExtractor) 与经历分析
│   ├── verification/         # 事实与经验有效性校验门禁
│   ├── consolidation/        # 冲突消解 (FactResolver) 与经验整合
│   ├── publication/          # 准入发布门禁与原子切换 (MemoryPublisher)
│   └── pipeline.py           # 离线处理流水线编排 (OfflineMemoryPipeline)
├── infrastructure/           # 存储层基础设施
│   ├── mysql/                # SQLAlchemy 2.0 异步仓储与表模型
│   └── milvus/               # Milvus 向量索引仓储
├── jobs/                     # 离线异步任务队列工作进程 (JobWorker)
├── reader/                   # 在线只读检索
│   ├── exact_retriever.py    # L0 精确槽位检索
│   ├── semantic_retriever.py # L1 向量语义检索
│   ├── applicability_filter.py # 运行时硬过滤门禁
│   ├── context_assembler.py  # 零 Tokenizer 预算贪心装配
│   └── recall_service.py     # 在线检索总线 (RecallService)
├── scripts/                  # 初始化与运维脚本 (init_db.py)
├── tests/                    # 完整自动化测试套件
│   ├── unit/                 # 单元测试
│   ├── integration/          # 接口集成测试
│   └── evaluation/           # 性能基准评测
├── config.py                 # 配置管理 (Pydantic Settings)
├── pyproject.toml            # 依赖项与 pytest 配置
└── .env.example              # 环境变量配置模板
```

---

## 三、快速开始

### 1. 安装依赖
推荐使用 Python 3.11+ 环境：
```bash
cd memory_service
pip install -e .
```

### 2. 配置环境变量
复制环境变量配置文件：
```bash
cp .env.example .env
```
根据实际环境修改 MySQL、Milvus 连接参数。本地单测默认使用 `aiosqlite` 内存数据库，无需额外中间件。

### 3. 初始化数据库与索引表
```bash
python scripts/init_db.py
```

### 4. 启动在线 API 服务
```bash
uvicorn api.main:app --host 0.0.0.0 --port 8000 --reload
```
服务健康检查：
```bash
curl http://localhost:8000/health
# 返回: {"status":"ok","service":"memory_service"}
```

---

## 四、REST API 接口规范

### 1. 在线记忆检索 (POST `/api/v1/memory/recall`)
**请求示例**：
```json
{
  "user_id": "user_1001",
  "query_text": "帮我看看现在适合买什么医药基金",
  "task_type": "fund_recommendation",
  "token_budget": 300,
  "context": {
    "domain": "finance",
    "market": "CN_A_SHARE_FUNDS"
  }
}
```

**响应示例**：
```json
{
  "compact_context": "### Long-Term Memory Context & Constraints\n- [FACT] user.risk_tolerance=进取型\n- [PREF] user.preferred_asset_classes=[\"ETF\", \"股票型\"]\n- [PREF] user.excluded_sectors=[\"白酒\"]",
  "recalled_items": [
    {
      "memory_id": "550e8400-e29b-41d4-a716-446655440000",
      "version": 1,
      "content": "User risk_tolerance is 进取型",
      "compact_text": "[FACT] user.risk_tolerance=进取型",
      "token_count": 8
    }
  ],
  "total_tokens": 32,
  "token_budget": 300
}
```

### 2. 离线会话记忆沉淀 (POST `/api/v1/memory/process-session`)
**请求示例**：
```json
{
  "session_id": "sess_88899",
  "user_id": "user_1001",
  "session_data": {
    "task_type": "portfolio_rebalance",
    "messages": [
      {"role": "user", "content": "我的风险偏好是进取型，主要买ETF，不考虑白酒"}
    ],
    "tool_calls": [
      {"tool_name": "fetch_portfolio", "args": {"user_id": "user_1001"}}
    ],
    "outcome": "SUCCESS"
  }
}
```

---

## 五、测试与性能基准

在 `memory_service/` 目录下执行全量测试：
```bash
pytest -v
```

执行延迟基准评测：
```bash
pytest tests/evaluation/ -s -v
```
**基准评测表现**：
- 在线只读召回平均延迟：**6.61 ms**
- P95 延迟：**10.43 ms**
- 预算遵守率：**100%**
- 测试用例通过率：**100% (20/20 Passed)**
