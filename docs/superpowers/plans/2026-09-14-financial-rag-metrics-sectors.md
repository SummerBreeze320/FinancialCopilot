# 基金指标与板块知识库混合 RAG 实施计划 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于 PostgreSQL (pgvector)、Neo4j 5.x 与本地 Ollama (`qwen3-embedding:0.6b` 1024 维)，构建基金指标 (`metrics.json` 169 项) 与板块分类树 (`sectors.json` 1,712 项) 的全栈 Java 混合 RAG 系统，向 Copilot Agent 暴露精准的指标助记码、板块 ID 对齐与金融概念问答工具。

**Architecture:** 
- **PostgreSQL 16 + pgvector**：元数据存储 + HNSW 1024 维余弦向量检索 + 精确/全文模糊检索三路融合。
- **Neo4j 5.20**：板块分类树 (`:FundSector`) 与指标分类体系 (`:MetricCategory`-`:FundMetric`) 拓扑模型，支撑子类下钻展开与路径回溯。
- **copilot-data-engine**：批量 Embedding 流水线、解析树生成、双写同步与混合检索调度。
- **copilot-agent-tools**：封装暴露 `FinancialSchemaRagTool`，支持 Tool-as-Truth 规范。

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data Neo4j (SDN) 5.x, PostgreSQL 16, pgvector (Java PGVector), Ollama API (`qwen3-embedding:0.6b`), JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-14-financial-rag-metrics-sectors-design.md`

## Global Constraints
- 向量维度必须严格为 1024 维，与本地运行中的 `qwen3-embedding:0.6b` 对齐。
- 指标主键为 `mnemonic` (VARCHAR 64)，板块主键为 `sector_id` (`source_sector_id` VARCHAR 32)。
- 代码与包结构严格遵循 DDD 六边形架构：领域实体与 Port 接口放入 `copilot-domain`，Adapter 与 Ingestion 放入 `copilot-data-engine`，Agent Tool 放入 `copilot-agent-tools`。
- 全量测试通过，不得影响现有的 `fund_report_vector`、`fund_info` 或 `FinancialGraphTool` 运行。

---

### Task 1: 数据库 DDL 与 Schema 初始化脚本

**Files:**
- Create: `docker/postgres/initdb/03_rag_schema.sql`

**Interfaces:**
- Produces: 物理表 `rag_fund_metric` 与 `rag_fund_sector`，含 HNSW 索引、BTree 索引及 pg_trgm 扩展与 GIN 索引。

- [ ] **Step 1: 编写 DDL 脚本文件**

编写 `docker/postgres/initdb/03_rag_schema.sql`，包含扩展 `CREATE EXTENSION IF NOT EXISTS vector;`、`CREATE EXTENSION IF NOT EXISTS pg_trgm;`、创建 `rag_fund_metric` 和 `rag_fund_sector` 表以及对应索引。

- [ ] **Step 2: 在当前运行的 PostgreSQL 容器中执行 DDL 验证**

Run: `docker exec -i docker_postgres_16 psql -U postgres -d financial_copilot -f /docker-entrypoint-initdb.d/03_rag_schema.sql` (或通过标准输入传入脚本执行)
Expected: `CREATE TABLE` and `CREATE INDEX` completed without errors.

- [ ] **Step 3: 验证表存在性与索引定义**

Run: `docker exec -i docker_postgres_16 psql -U postgres -d financial_copilot -c "\d rag_fund_metric" -c "\d rag_fund_sector"`
Expected: 显示两张表的列定义与 HNSW 向量索引 `vector_cosine_ops`。

- [ ] **Step 4: 提交 Task 1**

```bash
git add docker/postgres/initdb/03_rag_schema.sql
git commit -m "feat(rag): add postgres ddl for rag_fund_metric and rag_fund_sector"
```

---

### Task 2: 领域层实体与端口定义 (`copilot-domain`)

**Files:**
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/rag/entity/RagFundMetric.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/rag/entity/RagFundSector.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/rag/entity/SchemaRecallResult.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/rag/port/RagSchemaPort.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/rag/port/RagEmbeddingPort.java`
- Create: `copilot-domain/src/main/java/com/financial/copilot/domain/rag/port/RagGraphPort.java`
- Test: `copilot-domain/src/test/java/com/financial/copilot/domain/rag/RagDomainModelTest.java`

**Interfaces:**
- Produces:
  - `RagFundMetric(mnemonic, indexName, parentName, description, embeddingText, embedding, supportedUsage, aliases, enabled)`
  - `RagFundSector(sectorId, parentId, name, nameEn, aliases, description, embeddingText, embedding, isLeaf, elementType, treeLevel, fullPathNames, enabled)`
  - `SchemaRecallResult(matchedMetrics, matchedSectors, query)`
  - `RagSchemaPort`: `upsertMetrics(List<RagFundMetric>)`, `upsertSectors(List<RagFundSector>)`, `searchMetrics(...)`, `searchSectors(...)`
  - `RagEmbeddingPort`: `embed(String text)`, `batchEmbed(List<String> texts)`
  - `RagGraphPort`: `syncSectorGraph(List<RagFundSector>)`, `syncMetricGraph(List<RagFundMetric>)`, `expandLeafSectors(String sectorId)`, `findMetricCategoryAndSiblings(String mnemonic)`

- [ ] **Step 1: 编写领域模型单元测试（红灯验证）**

编写 `RagDomainModelTest.java` 验证实体的构建、不可变性与召回 DTO。

- [ ] **Step 2: 运行测试确认编译/执行失败**

Run: `mvn test -pl copilot-domain -Dtest=RagDomainModelTest`
Expected: FAIL (Class not found)

- [ ] **Step 3: 编写实体与 Port 接口实现**

创建上述 3 个实体记录（Record / POJO）与 3 个 Port 接口。

- [ ] **Step 4: 重新运行单元测试（绿灯验证）**

Run: `mvn test -pl copilot-domain -Dtest=RagDomainModelTest`
Expected: PASS (Tests run: 1, Failures: 0)

- [ ] **Step 5: 提交 Task 2**

```bash
git add copilot-domain/src/main/java/com/financial/copilot/domain/rag/
git add copilot-domain/src/test/java/com/financial/copilot/domain/rag/
git commit -m "feat(domain): add rag domain entities and ports for metrics and sectors"
```

---

### Task 3: 本地 Ollama 1024 维 Embedding 适配器 (`copilot-data-engine`)

**Files:**
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/embedding/OllamaEmbeddingAdapter.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/embedding/OllamaEmbeddingRequest.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/embedding/OllamaEmbeddingResponse.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/rag/embedding/OllamaEmbeddingAdapterTest.java`

**Interfaces:**
- Consumes: `RagEmbeddingPort`
- Produces: `OllamaEmbeddingAdapter implements RagEmbeddingPort`，调用 `http://127.0.0.1:11434/api/embed`，返回 `List<float[]>`（维度必须为 1024）。

- [ ] **Step 1: 编写 Embedding 适配器测试**

编写 `OllamaEmbeddingAdapterTest.java`，包含对 Ollama 请求体格式、响应解析（1024 维）、批次拆分（每批最大 32）以及异常重试的 Mock 测试。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl copilot-data-engine -Dtest=OllamaEmbeddingAdapterTest`
Expected: FAIL

- [ ] **Step 3: 编写 `OllamaEmbeddingAdapter` 及其 DTO**

使用 Spring 6 / Boot 3 `RestClient`（或 `RestTemplate` / Jackson）实现调用与批处理切分，模型配置取 `copilot.rag.embedding.model=qwen3-embedding:0.6b`，端点取 `copilot.rag.embedding.base-url=http://localhost:11434`。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl copilot-data-engine -Dtest=OllamaEmbeddingAdapterTest`
Expected: PASS

- [ ] **Step 5: 提交 Task 3**

```bash
git add copilot-data-engine/src/main/java/com/financial/copilot/data/rag/embedding/
git add copilot-data-engine/src/test/java/com/financial/copilot/data/rag/embedding/
git commit -m "feat(rag): implement ollama embedding adapter for qwen3 1024d model"
```

---

### Task 4: 数据解析器与板块分类树预处理器 (`copilot-data-engine`)

**Files:**
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/parser/MetricSectorDataParser.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/dto/MetricRawJsonDto.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/dto/SectorRawJsonDto.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/rag/parser/MetricSectorDataParserTest.java`

**Interfaces:**
- Produces:
  - `MetricSectorDataParser.parseMetrics(InputStream is): List<RagFundMetric>`
  - `MetricSectorDataParser.parseSectors(InputStream is): List<RagFundSector>`（自动递归计算 `tree_level` 和 `full_path_names`）

- [ ] **Step 1: 编写解析器测试**

测试读取真实文件 `docs/temp/metrics.json` 和 `docs/temp/sectors.json`：
- 校验指标数量为 169，助记码如 `f_return_1y` 属性正确映射；
- 校验板块数量为 1,712，根节点 `1000019220000000` 的 `tree_level == 0`，二级节点 `tree_level == 1`，叶子节点的 `full_path_names` 格式如 `内地公募基金 > Wind开放式基金分类 > 股票型基金 > 普通股票型基金`。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl copilot-data-engine -Dtest=MetricSectorDataParserTest`
Expected: FAIL

- [ ] **Step 3: 编写解析器与树遍历逻辑**

使用 Jackson `ObjectMapper` 反序列化为 DTO；对 Sectors 通过 Map 构建父子双向引用树，通过 BFS 计算深度与前缀路径。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl copilot-data-engine -Dtest=MetricSectorDataParserTest`
Expected: PASS (169 metrics and 1712 sectors verified)

- [ ] **Step 5: 提交 Task 4**

```bash
git add copilot-data-engine/src/main/java/com/financial/copilot/data/rag/parser/
git add copilot-data-engine/src/main/java/com/financial/copilot/data/rag/dto/
git add copilot-data-engine/src/test/java/com/financial/copilot/data/rag/parser/
git commit -m "feat(rag): add json parser and tree hierarchy flattener for metrics and sectors"
```

---

### Task 5: PostgreSQL 混合检索与 Neo4j 拓扑适配器 (`copilot-data-engine`)

**Files:**
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/adapter/PostgresRagSchemaAdapter.java`
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/adapter/Neo4jRagGraphAdapter.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/rag/adapter/PostgresRagSchemaAdapterTest.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/rag/adapter/Neo4jRagGraphAdapterTest.java`

**Interfaces:**
- Consumes: `RagSchemaPort`, `RagGraphPort`, `JdbcTemplate`, `Neo4jClient`
- Produces:
  - `PostgresRagSchemaAdapter implements RagSchemaPort`：包含向量余弦检索、模糊匹配与 RRF 融合 SQL。
  - `Neo4jRagGraphAdapter implements RagGraphPort`：包含 Cypher 树遍历与同类指标查询。

- [ ] **Step 1: 编写适配器测试代码**

编写测试验证 SQL 构造、pgvector 向量字符串转换（`[0.1,0.2,...]`）、Cypher UNWIND 批处理逻辑。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl copilot-data-engine -Dtest=PostgresRagSchemaAdapterTest`
Expected: FAIL

- [ ] **Step 3: 实现 `PostgresRagSchemaAdapter` 与 `Neo4jRagGraphAdapter`**

实现 `upsertMetrics`、`upsertSectors`、`searchMetrics`、`searchSectors`、`syncSectorGraph`、`expandLeafSectors` 等方法。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl copilot-data-engine -Dtest=PostgresRagSchemaAdapterTest,Neo4jRagGraphAdapterTest`
Expected: PASS

- [ ] **Step 5: 提交 Task 5**

```bash
git add copilot-data-engine/src/main/java/com/financial/copilot/data/rag/adapter/
git add copilot-data-engine/src/test/java/com/financial/copilot/data/rag/adapter/
git commit -m "feat(rag): implement postgres pgvector hybrid adapter and neo4j tree adapter"
```

---

### Task 6: 数据全量注入流水线与端到端入库测试 (`copilot-data-engine`)

**Files:**
- Create: `copilot-data-engine/src/main/java/com/financial/copilot/data/rag/service/RagSchemaSyncService.java`
- Test: `copilot-data-engine/src/test/java/com/financial/copilot/data/rag/service/RagSchemaSyncServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `MetricSectorDataParser`, `RagEmbeddingPort`, `RagSchemaPort`, `RagGraphPort`
- Produces: `RagSchemaSyncService.syncAll(Path metricsPath, Path sectorsPath): SyncReport`

- [ ] **Step 1: 编写集成测试**

编写 `RagSchemaSyncServiceIntegrationTest.java`：
- 读取真实 `docs/temp/metrics.json` 和 `docs/temp/sectors.json`；
- 调用本地实际运行的 Ollama Embedding 服务生成 1024 维向量；
- 批量写入真实 PostgreSQL 容器与 Neo4j 容器；
- 断言：
  - `SELECT COUNT(*) FROM rag_fund_metric` == 169
  - `SELECT COUNT(*) FROM rag_fund_sector` == 1712
  - Neo4j 中 `MATCH (s:FundSector) RETURN count(s)` == 1712
- 执行一次混合检索验证：“近1年回报” 能以高相似度召回 `f_return_1y`。

- [ ] **Step 2: 运行集成测试验证入库**

Run: `mvn test -pl copilot-data-engine -Dtest=RagSchemaSyncServiceIntegrationTest`
Expected: PASS，且控制台输出入库耗时与成功记录数。

- [ ] **Step 3: 验证数据库实体完整性**

Run: `docker exec -i docker_postgres_16 psql -U postgres -d financial_copilot -c "SELECT count(*), count(embedding) FROM rag_fund_metric;" -c "SELECT count(*), count(embedding) FROM rag_fund_sector;"`
Expected: `169 | 169`, `1712 | 1712`.

- [ ] **Step 4: 提交 Task 6**

```bash
git add copilot-data-engine/src/main/java/com/financial/copilot/data/rag/service/
git add copilot-data-engine/src/test/java/com/financial/copilot/data/rag/service/
git commit -m "feat(rag): implement end-to-end sync pipeline and ingest metrics and sectors"
```

---

### Task 7: Agent Tool 封装与暴露 (`copilot-agent-tools`)

**Files:**
- Create: `copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/rag/FinancialSchemaRagTool.java`
- Test: `copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/rag/FinancialSchemaRagToolTest.java`

**Interfaces:**
- Consumes: `RagEmbeddingPort`, `RagSchemaPort`, `RagGraphPort`
- Produces:
  - `matchMetricsAndSectors(String userQuery, Integer topK): String (JSON)`
  - `explainMetric(String metricOrName): String (JSON)`
  - `expandSector(String sectorIdOrName): String (JSON)`

- [ ] **Step 1: 编写 Tool 单元测试**

编写 `FinancialSchemaRagToolTest.java`，测试自然语言输入：
1. “帮我找近1年收益大于20%且最大回撤小于10%的基金” -> 返回包含 `f_return_1y` 与 `f_risk_maxdownside` 的 JSON。
2. “解释夏普比率” -> 返回释义及同分类指标推荐。
3. “中国上市ETF” -> 展开其下属二级与叶子分类板块。

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn test -pl copilot-agent-tools -Dtest=FinancialSchemaRagToolTest`
Expected: FAIL

- [ ] **Step 3: 实现 `FinancialSchemaRagTool`**

标注 `@Component`，实现三路混合检索调用、Neo4j 图谱叶子展开，并将结构化结果序列化为 JSON 字符串。

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn test -pl copilot-agent-tools -Dtest=FinancialSchemaRagToolTest`
Expected: PASS

- [ ] **Step 5: 提交 Task 7**

```bash
git add copilot-agent-tools/src/main/java/com/financial/copilot/agent/tools/rag/
git add copilot-agent-tools/src/test/java/com/financial/copilot/agent/tools/rag/
git commit -m "feat(agent-tools): add FinancialSchemaRagTool for natural language schema alignment and glossary"
```

---

### Task 8: 全工程构建与回归验证

**Files:**
- Modify: `copilot-app/src/main/resources/application.yml` (配置 RAG 相关配置项如 embedding baseUrl, modelName)

- [ ] **Step 1: 运行全模块 Maven 构建与测试**

Run: `mvn clean test`
Expected: BUILD SUCCESS，全部模块无报错。

- [ ] **Step 2: 提交 Task 8**

```bash
git add copilot-app/src/main/resources/application.yml
git commit -m "chore(config): finalize rag configuration and verify all tests pass"
```
