# 基金指标与板块知识库混合 RAG 架构设计规范

- **文档状态**：PROPOSED
- **创建日期**：2026-09-14
- **作者**：Antigravity Agent & System Architecture
- **涉及模块**：`copilot-domain`, `copilot-data-engine`, `copilot-agent-tools`, `docker`

---

## 1. 背景与目标

在 FinancialCopilot 投研助理交互场景中，用户通常使用自然语言表达选基或分析意图（例如：“*帮我找近1年收益率大于20%且最大回撤小于10%的医疗主题ETF*”）。大模型 Agent 要执行精准计算或构造结构化 SQL/Cypher 查询，必须跨过两道鸿沟：
1. **指标映射（Metric Mapping）**：将口语化指标名称（如“近1年收益”、“最大回撤”）精准映射为数据库标准物理字段与助记码（如 `f_return_1y`, `f_risk_maxdownside`）。
2. **板块与分类对齐（Sector Mapping）**：将模糊分类（如“医疗主题”、“ETF”）映射至标准板块分类树（如 `1000009160000000` 中国上市ETF，`1000022491000000` 医疗健康），并在需要时展开所有的下级叶子板块节点。
3. **金融概念问答（Financial Glossary Q&A）**：回答指标的口径定义、计算说明及分类归属。

本项目当前具备：
- **PostgreSQL 16 + pgvector** 关系与高维向量引擎
- **Neo4j 5.20** 图数据库引擎（已挂载 APOC）
- **Ollama 本地 Embedding 服务**（`qwen3-embedding:0.6b`，1024 维）
- 数据源：`docs/temp/metrics.json`（169 项核心基金指标）和 `docs/temp/sectors.json`（1,712 项板块与分类树）

本设计确立了 **分工协同式混合 RAG（Hybrid Tri-Store Architecture）**，打通全栈 Java/Spring Boot 工程链路。

---

## 2. 总体架构设计

系统划分为 **数据注入层（Ingestion Pipeline）**、**存储融合底座（Tri-Store）**、**混合召回与图推理层（Hybrid Retriever & Reasoner）** 和 **Agent 工具层（Tool-as-Truth）**：

```mermaid
flowchart TD
    subgraph 原始数据源
        F_M[docs/temp/metrics.json<br/>169 项指标]
        F_S[docs/temp/sectors.json<br/>1,712 项分类树]
    end

    subgraph 数据注入流水线 copilot-data-engine
        SYNC[RagSchemaSyncService]
        TREE_CALC[树形层级与全路径计算]
        EMB_CLI[OllamaEmbeddingClient<br/>批次32 / 1024维]
    end

    F_M --> SYNC
    F_S --> TREE_CALC --> SYNC
    SYNC --> EMB_CLI

    subgraph 混合存储层
        PG[(PostgreSQL 16 + pgvector<br/>rag_fund_metric<br/>rag_fund_sector<br/>HNSW & GIN 索引)]
        NEO4J[(Neo4j 5.20<br/>:FundSector 分类树<br/>:MetricCategory - :FundMetric)]
    end

    EMB_CLI -->|批量写入| PG
    SYNC -->|Cypher UNWIND 批量挂载| NEO4J

    subgraph 混合召回引擎 copilot-data-engine
        RETRIEVER[HybridRagRetriever]
        RETRIEVER -->|精确+模糊+向量三路| PG
        RETRIEVER -->|拓扑递归与叶子展开| NEO4J
    end

    subgraph 智能体工具层 copilot-agent-tools
        TOOL[FinancialSchemaRagTool<br/>@Component]
        AGENT[Financial Research Agent / LLM]
    end

    AGENT -->|调用| TOOL
    TOOL -->|调度| RETRIEVER
```

---

## 3. 存储层详细设计

### 3.1 PostgreSQL 物理表结构 (DDL)

#### (1) 基金指标表 `rag_fund_metric`
```sql
CREATE TABLE IF NOT EXISTS rag_fund_metric (
    mnemonic VARCHAR(64) PRIMARY KEY,                  -- 指标助记码 (如: f_return_1y, f_risk_maxdownside)
    index_name VARCHAR(128) NOT NULL,                  -- 指标中文名
    parent_name VARCHAR(64) NOT NULL,                  -- 所属大类 (收益指标/风险指标/通用指标等)
    description TEXT,                                  -- 业务释义与口径说明
    embedding_text TEXT NOT NULL,                      -- 向量化原始语料
    embedding vector(1024),                            -- qwen3-embedding 1024 维向量
    source_indicator_id BIGINT,                        -- 原始数据源 ID
    supported_usage VARCHAR(32)[],                     -- 支持用法: ARRAY['filter', 'sort']
    applicable_products VARCHAR(64),                   -- 适用产品类别代码
    aliases TEXT[],                                    -- 别名同义词数组
    version INT DEFAULT 1,
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rag_metric_embedding_hnsw 
ON rag_fund_metric USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_rag_metric_parent ON rag_fund_metric(parent_name);
CREATE INDEX IF NOT EXISTS idx_rag_metric_usage ON rag_fund_metric USING gin(supported_usage);
CREATE INDEX IF NOT EXISTS idx_rag_metric_aliases ON rag_fund_metric USING gin(aliases);
CREATE INDEX IF NOT EXISTS idx_rag_metric_name_trgm ON rag_fund_metric USING gin (index_name gin_trgm_ops);
```

#### (2) 基金板块分类表 `rag_fund_sector`
```sql
CREATE TABLE IF NOT EXISTS rag_fund_sector (
    sector_id VARCHAR(32) PRIMARY KEY,                 -- 板块编码 (source_sector_id)
    parent_id VARCHAR(32),                             -- 上级板块编码 (根节点为 NULL)
    name VARCHAR(128) NOT NULL,                        -- 板块中文名称
    name_en VARCHAR(128),                              -- 英文名称
    aliases TEXT[],                                    -- 别名同义词数组
    description TEXT,                                  -- 板块描述
    embedding_text TEXT NOT NULL,                      -- 向量化原始语料
    embedding vector(1024),                            -- qwen3-embedding 1024 维向量
    is_leaf BOOLEAN DEFAULT TRUE,                      -- 是否为叶子分类节点
    element_type INT DEFAULT 6,                        -- 分类类型
    tree_level INT DEFAULT 0,                          -- 层级深度 (根节点为 0, 子节点递增)
    full_path_names TEXT,                              -- 完整分类层级链 (如: '内地公募基金 > 基金市场类 > 开放式基金')
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_rag_sector_embedding_hnsw 
ON rag_fund_sector USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_rag_sector_parent ON rag_fund_sector(parent_id);
CREATE INDEX IF NOT EXISTS idx_rag_sector_leaf ON rag_fund_sector(is_leaf);
CREATE INDEX IF NOT EXISTS idx_rag_sector_aliases ON rag_fund_sector USING gin(aliases);
CREATE INDEX IF NOT EXISTS idx_rag_sector_name_trgm ON rag_fund_sector USING gin (name gin_trgm_ops);
```

---

### 3.2 Neo4j 拓扑模型与 Cypher 规范

#### 节点与关系模型：
1. **板块树**：
   - 节点标签：`(:FundSector {sector_id: String, name: String, is_leaf: Boolean, tree_level: Integer, full_path: String})`
   - 关系：`(:FundSector)-[:PARENT_OF]->(:FundSector)`
2. **指标大类与指标**：
   - 节点标签：`(:MetricCategory {name: String})`
   - 节点标签：`(:FundMetric {mnemonic: String, name: String, description: String, supported_usage: List<String>})`
   - 关系：`(:MetricCategory)-[:CONTAINS_METRIC]->(:FundMetric)`
3. **资产穿透挂载点**：
   - 保留扩展关系：`(:Fund)-[:BELONGS_TO_SECTOR]->(:FundSector)`

#### 约束与索引：
```cypher
CREATE CONSTRAINT cstr_sector_id IF NOT EXISTS FOR (s:FundSector) REQUIRE s.sector_id IS UNIQUE;
CREATE CONSTRAINT cstr_metric_mnemonic IF NOT EXISTS FOR (m:FundMetric) REQUIRE m.mnemonic IS UNIQUE;
CREATE CONSTRAINT cstr_metric_cat IF NOT EXISTS FOR (c:MetricCategory) REQUIRE c.name IS UNIQUE;
CREATE INDEX idx_sector_name IF NOT EXISTS FOR (s:FundSector) ON (s.name);
CREATE INDEX idx_metric_name IF NOT EXISTS FOR (m:FundMetric) ON (m.name);
```

---

## 4. 向量化与数据同步流水线 (`copilot-data-engine`)

### 4.1 树形解析与层级打平
在数据加载时，将 `sectors.json` 的 1,712 条记录根据 `parent_id` 构建有向树：
- 确定唯一根节点：`1000019220000000` (内地公募基金)
- 广度优先遍历计算每个节点的深度 `tree_level`；
- 构造 `full_path_names`（格式：`根分类 > 一级分类 > 二级分类 > 当前分类`）；
- 将全路径追加至 `embedding_text` 结尾，增强高维语义上下文。

### 4.2 Ollama 批量 Embedding 客户端
- 请求端点：`POST http://localhost:11434/api/embed`
- 请求模型：`qwen3-embedding:0.6b`
- 批次大小：每批 32 条文本；
- 指标处理：169 条 / 32 = 6 个批次（耗时约 1.5s）；
- 板块处理：1,712 条 / 32 = 54 个批次（RTX 3060 耗时约 12s）；
- 异常重试：具备 3 次退避重试机制。

### 4.3 批量入库事务控制
1. **PG 写入**：`JdbcTemplate.batchUpdate` + `ON CONFLICT DO UPDATE` 实现幂等全量同步。
2. **Neo4j 写入**：采用 Cypher `UNWIND $batch AS item` 分批提交（每批 200 条），规避长事务。

---

## 5. 混合检索与图增强召回引擎

### 5.1 三路融合算法
针对用户 Query $Q$：
1. **精确路由（Exact Boost）**：若 $Q$ 精确包含某指标助记码 `mnemonic` 或板块完整别名，该候选赋予基础保底分 1.0；
2. **文本模糊（Trigram Lexical）**：利用 `pg_trgm` 的 `similarity()` 计算词面匹配分 $S_{text}$；
3. **语义向量（Dense Vector）**：调用 Ollama 获取 Query 向量后，利用 HNSW 余弦相似度计算 $S_{vec} = 1 - (embedding \Leftrightarrow q\_vec)$；
4. **线性融合评分**：
   $$S_{final} = 0.7 \times S_{vec} + 0.3 \times S_{text} + Boost_{exact}$$

### 5.2 Neo4j 拓扑展开策略
1. **下钻穿透（Parent to Leaves）**：
   若召回的板块 $S$ 为非叶子节点（`is_leaf = false`），检索其下游所有深度 1~4 的叶子节点：
   ```cypher
   MATCH (p:FundSector {sector_id: $sectorId})-[:PARENT_OF*1..4]->(leaf:FundSector {is_leaf: true})
   RETURN leaf.sector_id, leaf.name;
   ```
   返回给下游查询引擎使用（例如支持用户查“宽基ETF”，自动展开为沪深300ETF、中证500ETF等具体叶子代码）。
2. **同族扩展（Sibling Expansion）**：
   当用户查询单一指标时，返回同类目下的Top-3衍生指标推荐，用于丰富 Prompt 上下文。

---

## 6. 接口与 Agent Tool 规范 (`copilot-agent-tools`)

### 6.1 工具类：`FinancialSchemaRagTool`
注册为 Spring Bean，供 Agent 运行时通过反射或 Tool 路由调用。

#### 核心方法 1：`matchMetricsAndSectors(String userQuery, Integer topK)`
- **说明**：自然语言选基与条件解析的主入口。
- **返回数据结构**：
  - `matched_metrics`：助记码、指标名称、类别、支持用法（filter/sort）、相似度得分；
  - `matched_sectors`：板块 ID、板块名称、完整层级链、是否叶子节点、展开后的叶子 ID 列表。

#### 核心方法 2：`explainMetric(String metricOrName)`
- **说明**：知识问答与概念解析。
- **返回数据结构**：包含口径说明、英文名、所属分类及同族指标关联。

#### 核心方法 3：`expandSector(String sectorIdOrName)`
- **说明**：分类体系树状结构下钻与向上追溯。

---

## 7. 模块演进与代码变更清单

1. **`copilot-domain`**：
   - 新增领域实体：`RagFundMetric.java`, `RagFundSector.java`, `SchemaRecallResult.java`
   - 新增端口契约：`RagSchemaPort.java`, `RagEmbeddingPort.java`, `RagGraphPort.java`
2. **`copilot-data-engine`**：
   - 新增适配器：
     - `OllamaEmbeddingAdapter.java`（对接本地 Ollama）
     - `PostgresRagSchemaAdapter.java`（PG 16 + pgvector）
     - `Neo4jRagGraphAdapter.java`（Neo4j 树遍历）
   - 新增同步服务：`RagSchemaSyncService.java`（数据解析与双写流水线）
   - 新增 DDL 脚本：`docker/postgres/initdb/03_rag_schema.sql`
3. **`copilot-agent-tools`**：
   - 新增工具：`FinancialSchemaRagTool.java`
4. **集成测试**：
   - `RagSchemaDataSyncIntegrationTest.java`：端到端验证 169 个指标与 1,712 个板块向量化入库与三路混合检索召回。

---

## 8. 验收标准

1. **入库完整性**：
   - `SELECT COUNT(*) FROM rag_fund_metric` 等于 169；
   - `SELECT COUNT(*) FROM rag_fund_sector` 等于 1,712；
   - Neo4j 中 `MATCH (s:FundSector) RETURN count(s)` 等于 1,712，根节点 `1000019220000000` 完整关联 23 个一级子分类。
2. **检索准确率**：
   - 输入 “近1年回报” 能以 >0.9 相似度准确召回 `f_return_1y`；
   - 输入 “最大回撤” 能准确召回 `f_risk_maxdownside`；
   - 输入 “招商银行代销” 能准确召回 `1000002364000000` 等相关代销板块；
   - 输入 “ETF” 能召回 `中国上市ETF` 并展开下游叶子板块。
