# 金融知识库 RAG 服务 (financial-knowledge-rag) 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建独立的 Python 金融知识库 RAG 微服务 `financial-knowledge-rag`，提供针对 PDF（原生与扫描件）、Excel、Word、Markdown 的表格感知切块与多模态流程图转译能力，基于 PostgreSQL `pgvector` 与全文检索双路召回及本地 GPU 神经重排器，为多智能体交付高置信度 Top-K 结构化片段与全流程分段归因监控。

**Architecture:** 采用模块化分层流水线架构（`ingest/`、`retrieval/`、`diagnostics/`、`api/`）。以 `POST /api/knowledge/retrieve` 交付 Top-K 结构化 Chunks 为核心能力；多格式解析器保护复杂财务表格完整性并在切分时强制挂顶表头；利用本地 GPU 运行 OCR 与 Cross-Encoder 神经重排；通过 `diagnostics` 提供毫秒级耗时透视与四步精度根因归因决策树。

**Tech Stack:** Python 3.11+ / FastAPI / Uvicorn / AsyncPG / PostgreSQL 16 (pgvector + pg_trgm) / PyMuPDF (fitz) / pdfplumber / openpyxl / python-docx / rapidocr-onnxruntime / BAAI bge-reranker-large (Cross-Encoder) / 本地 Codex-Bridge (Gemini 3.8 Flash) / 本地 Ollama (qwen3-embedding:0.6b).

**Spec:** [docs/superpowers/specs/2026-10-24-financial-knowledge-rag-design.md](file:///D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/docs/superpowers/specs/2026-10-24-financial-knowledge-rag-design.md)

## Global Constraints

- **服务端口**：默认监听 `8820`，可通过 `RAG_PORT` 环境变量配置。
- **LLM 与 VLM 标准规范**：遵循用户全局规则，默认使用本地 `codex-bridge`（Base URL: `http://127.0.0.1:9090/v1`，API Key: `local-agy`，Model: `gemini-3.8-flash-low`）。
- **向量维度与 Embedding 标准**：默认 1024 维，对接本地 Ollama（`http://127.0.0.1:11434/v1`，Model: `qwen3-embedding:0.6b`）。
- **数据库规范**：复用现有 PostgreSQL 实例（`postgresql://postgres:123456@127.0.0.1:15432/financial_copilot`），启用 `vector` 与 `pg_trgm` 扩展。
- **硬件降级规范**：GPU OCR 与 GPU Reranker 必须支持无 CUDA 时的 CPU / RRF 算法优雅降级。
- **代码干净度规范**：全代码类型注解、详尽中文文档注释、单测严密覆盖。

---

### Task 1: 项目脚手架搭建、依赖配置与数据库表结构初始化

**Files:**
- Create: `financial-knowledge-rag/pyproject.toml`
- Create: `financial-knowledge-rag/.env.example`
- Create: `financial-knowledge-rag/app/__init__.py`
- Create: `financial-knowledge-rag/app/config.py`
- Create: `financial-knowledge-rag/scripts/init_db.py`
- Create: `financial-knowledge-rag/tests/conftest.py`
- Test: `financial-knowledge-rag/tests/unit/test_config.py`

**Interfaces:**
- Produces: `app.config.Settings` 配置对象，包含数据库、LLM、Embedding、端口、超参数定义。
- Produces: `scripts.init_db.init_database()` 异步执行建表函数。

- [ ] **Step 1: 编写测试用例 `test_config.py`**

```python
# financial-knowledge-rag/tests/unit/test_config.py
import pytest
from app.config import Settings

def test_settings_defaults():
    settings = Settings()
    assert settings.port == 8820
    assert settings.embedding_dim == 1024
    assert settings.llm_base_url == "http://127.0.0.1:9090/v1"
    assert settings.llm_model == "gemini-3.8-flash-low"
    assert settings.top_k_default == 5
    assert settings.candidate_pool_size == 30
    assert settings.reranker_type in ("neural", "rrf", "hybrid")
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
cd d:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\financial-knowledge-rag
pytest tests/unit/test_config.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app')

- [ ] **Step 3: 编写 `pyproject.toml`、`app/config.py` 与 `scripts/init_db.py`**

在 `pyproject.toml` 中配置核心依赖：
- `fastapi>=0.115.0`
- `uvicorn[standard]>=0.30.0`
- `pydantic>=2.9.0`
- `pydantic-settings>=2.5.0`
- `asyncpg>=0.29.0`
- `httpx>=0.27.0`
- `pymupdf>=1.24.0`
- `pdfplumber>=0.11.0`
- `openpyxl>=3.1.0`
- `python-docx>=1.1.0`
- `pandas>=2.2.0`
- `sentence-transformers>=3.0.0`
- `rapidocr-onnxruntime>=1.3.0`
- `pytest>=8.0.0`, `pytest-asyncio>=0.24.0`

在 `app/config.py` 中编写带环境变量读取的 `Settings`，在 `scripts/init_db.py` 中编写 `CREATE EXTENSION IF NOT EXISTS vector;` 以及创建 `knowledge_documents` 与 `knowledge_chunks` 表的幂等 DDL。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_config.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/
git commit -m "feat(rag): scaffold financial-knowledge-rag with config and db init schema"
```

---

### Task 2: 核心数据模型与 API DTO 契约实现

**Files:**
- Create: `financial-knowledge-rag/app/api/__init__.py`
- Create: `financial-knowledge-rag/app/api/models.py`
- Test: `financial-knowledge-rag/tests/unit/test_models.py`

**Interfaces:**
- Produces: `RetrieveRequest`, `RetrieveResponse`, `ChunkDTO`, `DiagnosticsDTO`, `DocumentUploadResponse`, `DocumentStatusResponse`。

- [ ] **Step 1: 编写数据模型契约单测 `test_models.py`**

```python
# financial-knowledge-rag/tests/unit/test_models.py
from datetime import date
from app.api.models import RetrieveRequest, RetrieveResponse, ChunkDTO, DiagnosticsDTO

def test_retrieve_request_validation():
    req = RetrieveRequest(query="宁德时代出货量", top_k=5, score_threshold=0.4)
    assert req.query == "宁德时代出货量"
    assert req.top_k == 5
    assert req.rerank_strategy == "neural"

def test_chunk_dto_serialization():
    chunk = ChunkDTO(
        chunk_id="chunk_01",
        document_id="doc_01",
        document_title="宁德时代研报",
        doc_type="RESEARCH_REPORT",
        publish_date=date(2024, 10, 20),
        page_number=3,
        section_title="盈利拆解",
        content="2024Q3动力电池出货260GWh...",
        score=0.91,
        recall_sources=["VECTOR", "FULLTEXT"],
        has_table=True,
        metadata={"stock_codes": ["300750"]}
    )
    assert chunk.has_table is True
    assert "300750" in chunk.metadata["stock_codes"]
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/unit/test_models.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app.api.models')

- [ ] **Step 3: 实现 `app/api/models.py` 完整 Pydantic 模型**

包含：
- `FilterParams`: `stock_codes`, `doc_types`, `institutions`, `publish_date_start`, `publish_date_end`；
- `RetrieveRequest`: `query`, `top_k`, `score_threshold`, `filters`, `rerank_strategy`, `explain`；
- `ChunkDTO`: `chunk_id`, `document_id`, `document_title`, `institution`, `publish_date`, `page_number`, `section_title`, `content`, `score`, `recall_sources`, `has_table`, `metadata`；
- `DiagnosticsDTO`: `latency_breakdown_ms`, `recall_funnel`, `candidate_rank_shift`, `warnings`；
- `RetrieveResponse`: `query`, `total_candidates`, `chunks`, `diagnostics`。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_models.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/app/api/models.py financial-knowledge-rag/tests/unit/test_models.py
git commit -m "feat(rag): add domain DTO models and request response schemas"
```

---

### Task 3: 多格式文档解析器与表格感知切块器实现 (Excel/Word/MD/PDF)

**Files:**
- Create: `financial-knowledge-rag/app/ingest/__init__.py`
- Create: `financial-knowledge-rag/app/ingest/splitter.py`
- Create: `financial-knowledge-rag/app/ingest/excel_parser.py`
- Create: `financial-knowledge-rag/app/ingest/docx_parser.py`
- Create: `financial-knowledge-rag/app/ingest/markdown_parser.py`
- Create: `financial-knowledge-rag/app/ingest/pdf_parser.py`
- Create: `financial-knowledge-rag/app/ingest/parser_registry.py`
- Test: `financial-knowledge-rag/tests/unit/test_parsers.py`

**Interfaces:**
- Produces: `TableAwareSplitter.split_text_with_tables(text, metadata)` -> `List[ParsedChunk]`。
- Produces: `ParserRegistry.get_parser(file_extension)` -> `BaseDocumentParser`。
- Guarantee: 跨块表格每个子块顶部均复制主表头；绝不跨行腰斩。

- [ ] **Step 1: 编写解析器与表头保留切块单测 `test_parsers.py`**

```python
# financial-knowledge-rag/tests/unit/test_parsers.py
from app.ingest.splitter import TableAwareSplitter

def test_table_aware_splitter_header_duplication():
    splitter = TableAwareSplitter(chunk_size=150, chunk_overlap=30)
    markdown_table = (
        "### 财务摘要表\n"
        "| 指标 | 2022 | 2023 | 2024E |\n"
        "| :--- | :--- | :--- | :--- |\n"
        "| 营业收入(亿元) | 100 | 150 | 220 |\n"
        "| 净利润(亿元) | 10 | 18 | 28 |\n"
        "| 毛利率(%) | 25.0 | 26.5 | 28.0 |\n"
        "| 净利率(%) | 10.0 | 12.0 | 12.7 |\n"
        "| ROE(%) | 15.0 | 18.0 | 21.0 |\n"
    )
    chunks = splitter.split_markdown(markdown_table, metadata={"doc_id": "test_01"})
    assert len(chunks) >= 2
    # 验证第二个 chunk 依然保留表头行
    for c in chunks:
        assert "| 指标 | 2022 | 2023 | 2024E |" in c.content
        assert c.has_table is True
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/unit/test_parsers.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app.ingest')

- [ ] **Step 3: 实现 `TableAwareSplitter` 与各格式解析器**

- `excel_parser.py`：读取 sheets，合并单元格前向填充，转为 Markdown 表；
- `docx_parser.py`：解析大纲标题与表格；
- `markdown_parser.py`：切分段落与 Markdown 表；
- `pdf_parser.py`：PyMuPDF 探测字符流，原生页使用 pdfplumber 抽取表格和文字，采用生成器流式处理；
- `parser_registry.py`：统一入口 `parse_document(file_path, filename) -> List[ParsedChunk]`。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_parsers.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/app/ingest/ financial-knowledge-rag/tests/unit/test_parsers.py
git commit -m "feat(rag): implement multi-format parsers and table-aware chunk splitter"
```

---

### Task 4: GPU OCR 引擎与 Codex-Bridge VLM 流程图解析器实现

**Files:**
- Create: `financial-knowledge-rag/app/ingest/ocr_engine.py`
- Create: `financial-knowledge-rag/app/ingest/vlm_analyzer.py`
- Test: `financial-knowledge-rag/tests/unit/test_ocr_and_vlm.py`

**Interfaces:**
- Produces: `OcrEngine.recognize_page(image_bytes)` -> 识别文字与表格坐标，支持 CUDA 自动检测与 CPU 降级。
- Produces: `VlmAnalyzer.analyze_diagram(image_bytes, caption)` -> 转译复杂流程图/股权架构图为结构化描述与 Mermaid 语法。

- [ ] **Step 1: 编写 OCR 与 VLM 单测 `test_ocr_and_vlm.py`**

```python
# financial-knowledge-rag/tests/unit/test_ocr_and_vlm.py
import pytest
from unittest.mock import AsyncMock, patch
from app.ingest.vlm_analyzer import VlmAnalyzer

@pytest.mark.asyncio
async def test_vlm_analyzer_converts_flowchart():
    analyzer = VlmAnalyzer(base_url="http://127.0.0.1:9090/v1", api_key="local-agy")
    with patch("httpx.AsyncClient.post") as mock_post:
        mock_post.return_value = AsyncMock(
            status_code=200,
            json=lambda: {
                "choices": [{"message": {"content": "【股权架构流程】\n```mermaid\ngraph TD\nA[母公司] --> B[子公司]\n```"}}]
            }
        )
        res = await analyzer.analyze_diagram(b"fake_image_bytes", diagram_hint="股权结构图")
        assert "【股权架构流程】" in res
        assert "mermaid" in res
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/unit/test_ocr_and_vlm.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app.ingest.vlm_analyzer')

- [ ] **Step 3: 实现 `ocr_engine.py` 与 `vlm_analyzer.py`**

- `ocr_engine.py`：检查 `torch.cuda.is_available()`，启用 GPU ONNX/PaddleOCR，失败时优雅降级 CPU；
- `vlm_analyzer.py`：异步 HTTPX 调用 Codex-Bridge `/v1/chat/completions` 多模态端点，封装专用金融图表 System Prompt。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_ocr_and_vlm.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/app/ingest/ocr_engine.py financial-knowledge-rag/app/ingest/vlm_analyzer.py financial-knowledge-rag/tests/unit/test_ocr_and_vlm.py
git commit -m "feat(rag): add GPU OCR engine and Codex-Bridge VLM diagram translator"
```

---

### Task 5: 本地 Embedding 客户端与 PostgreSQL pgvector 异步持久化

**Files:**
- Create: `financial-knowledge-rag/app/retrieval/__init__.py`
- Create: `financial-knowledge-rag/app/retrieval/embedding_client.py`
- Create: `financial-knowledge-rag/app/retrieval/pgvector_store.py`
- Test: `financial-knowledge-rag/tests/unit/test_embedding_and_store.py`

**Interfaces:**
- Produces: `EmbeddingClient.embed_texts(texts: List[str])` -> `List[List[float]]` (1024 维，批量调用带重试)。
- Produces: `PgVectorStore.save_chunks(chunks: List[ChunkDTO], embeddings: List[List[float]])`。
- Produces: `PgVectorStore.vector_search(query_embedding, top_k, filters)` -> `List[ScoredCandidate]`。
- Produces: `PgVectorStore.fulltext_search(query_text, top_k, filters)` -> `List[ScoredCandidate]`。

- [ ] **Step 1: 编写客户端与存储逻辑单测 `test_embedding_and_store.py`**

```python
# financial-knowledge-rag/tests/unit/test_embedding_and_store.py
import pytest
from unittest.mock import AsyncMock, patch
from app.retrieval.embedding_client import EmbeddingClient

@pytest.mark.asyncio
async def test_embedding_client_batching():
    client = EmbeddingClient(base_url="http://127.0.0.1:11434/v1", model="qwen3-embedding:0.6b", dim=1024)
    with patch("httpx.AsyncClient.post") as mock_post:
        mock_post.return_value = AsyncMock(
            status_code=200,
            json=lambda: {
                "data": [
                    {"embedding": [0.1] * 1024, "index": 0},
                    {"embedding": [0.2] * 1024, "index": 1}
                ]
            }
        )
        vectors = await client.embed_texts(["文本1", "文本2"])
        assert len(vectors) == 2
        assert len(vectors[0]) == 1024
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/unit/test_embedding_and_store.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app.retrieval.embedding_client')

- [ ] **Step 3: 实现 `embedding_client.py` 与 `pgvector_store.py`**

- `embedding_client.py`：支持批量切片并发调用，失败指数退避重试；
- `pgvector_store.py`：使用 `asyncpg` 连接池，执行向量余弦检索 `<=>` 和 `ts_rank_cd(tsv_content, plainto_tsquery('chinese', ...))` 全文检索。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_embedding_and_store.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/app/retrieval/ financial-knowledge-rag/tests/unit/test_embedding_and_store.py
git commit -m "feat(rag): implement embedding client and asyncpg vector fulltext store"
```

---

### Task 6: 双路并发召回、GPU 神经重排器与金融业务加权

**Files:**
- Create: `financial-knowledge-rag/app/retrieval/hybrid_retriever.py`
- Create: `financial-knowledge-rag/app/retrieval/reranker.py`
- Test: `financial-knowledge-rag/tests/unit/test_hybrid_retrieval.py`

**Interfaces:**
- Produces: `HybridRetriever.retrieve_candidates(query, filters, top_k=30)` -> 并发召回并去重得到 30~50 候选集。
- Produces: `NeuralReranker.rerank(query, candidates, top_k=5, threshold=0.35)` -> GPU Cross-Encoder 精排打分并结合股票代码/表格加权。

- [ ] **Step 1: 编写双路召回与重排单测 `test_hybrid_retrieval.py`**

```python
# financial-knowledge-rag/tests/unit/test_hybrid_retrieval.py
import pytest
from app.retrieval.reranker import NeuralReranker, ScoredCandidate

def test_reranker_boosts_exact_stock_code():
    reranker = NeuralReranker(model_name=None, use_gpu=False) # 算法降级模式测试加权逻辑
    candidates = [
        ScoredCandidate(chunk_id="c1", content="宁德时代研发投入...", score=0.75, metadata={"stock_codes": ["300750"]}, has_table=False),
        ScoredCandidate(chunk_id="c2", content="新能源行业通用概况...", score=0.78, metadata={"stock_codes": []}, has_table=False),
    ]
    reranked = reranker.apply_financial_boosts(query="300750 三季报研发", candidates=candidates)
    # 命中 300750 获得 +0.15 加权后 c1 逆袭 c2
    assert reranked[0].chunk_id == "c1"
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/unit/test_hybrid_retrieval.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app.retrieval.reranker')

- [ ] **Step 3: 实现 `hybrid_retriever.py` 与 `reranker.py`**

- `hybrid_retriever.py`：`asyncio.gather` 并发执行向量召回与全文召回，合并候选集；
- `reranker.py`：载入 `CrossEncoder('BAAI/bge-reranker-large')`，利用 GPU 批处理打分；附加股票代码精确匹配加权（+0.15）、财务表格意图加权（+0.10）与阈值截断；无 GPU 或模型时自动降级 RRF 算法。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_hybrid_retrieval.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/app/retrieval/hybrid_retriever.py financial-knowledge-rag/app/retrieval/reranker.py financial-knowledge-rag/tests/unit/test_hybrid_retrieval.py
git commit -m "feat(rag): implement hybrid retriever and GPU neural cross-encoder reranker"
```

---

### Task 7: 全链路分段归因监控与位移透视诊断器

**Files:**
- Create: `financial-knowledge-rag/app/diagnostics/__init__.py`
- Create: `financial-knowledge-rag/app/diagnostics/tracer.py`
- Create: `financial-knowledge-rag/app/diagnostics/explainer.py`
- Test: `financial-knowledge-rag/tests/unit/test_diagnostics.py`

**Interfaces:**
- Produces: `DiagnosticTracer`: 毫秒级分段计时（Embedding, Vector, Fulltext, Rerank）。
- Produces: `AttributionExplainer`: 计算 candidate rank shift（位移分析）、候选池饱和度截断告警、四步根因分类。

- [ ] **Step 1: 编写诊断与位移分析单测 `test_diagnostics.py`**

```python
# financial-knowledge-rag/tests/unit/test_diagnostics.py
from app.diagnostics.explainer import AttributionExplainer

def test_explainer_detects_candidate_pool_saturation():
    explainer = AttributionExplainer()
    # 最终 Top 1 的 Chunk 在粗召回阶段排在第 29 位 (接近上限 30)
    shift_data = [
        {"chunk_id": "c_29", "initial_rank": 29, "final_rank": 1, "score": 0.92}
    ]
    warnings = explainer.check_saturation_warning(shift_data, pool_size=30)
    assert len(warnings) >= 1
    assert "Candidate Pool Saturation" in warnings[0]
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/unit/test_diagnostics.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app.diagnostics')

- [ ] **Step 3: 实现 `tracer.py` 与 `explainer.py`**

- `tracer.py`：异步上下文管理器统计各阶段耗时；
- `explainer.py`：生成 `DiagnosticsDTO`，计算召回漏斗数量、前后位移状态与根因决策。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_diagnostics.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/app/diagnostics/ financial-knowledge-rag/tests/unit/test_diagnostics.py
git commit -m "feat(rag): implement diagnostic tracer and rank shift attribution explainer"
```

---

### Task 8: FastAPI 核心路由、端到端接口与集成测试

**Files:**
- Create: `financial-knowledge-rag/app/api/routes.py`
- Create: `financial-knowledge-rag/app/generator/__init__.py`
- Create: `financial-knowledge-rag/app/generator/qa_generator.py`
- Create: `financial-knowledge-rag/app/main.py`
- Test: `financial-knowledge-rag/tests/integration/test_retrieve_api.py`

**Interfaces:**
- Exposes: `POST /api/knowledge/retrieve` (核心检索)
- Exposes: `POST /api/knowledge/documents/upload` (文档入库)
- Exposes: `GET /api/knowledge/documents/{id}/status` (状态查询)
- Exposes: `POST /api/knowledge/query/stream` (SSE 问答流)

- [ ] **Step 1: 编写 API 端到端集成测试 `test_retrieve_api.py`**

```python
# financial-knowledge-rag/tests/integration/test_retrieve_api.py
import pytest
from httpx import AsyncClient, ASGITransport
from app.main import app

@pytest.mark.asyncio
async def test_retrieve_endpoint_flow():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        payload = {
            "query": "宁德时代2024Q3动力电池毛利率",
            "top_k": 3,
            "explain": True
        }
        res = await ac.post("/api/knowledge/retrieve", json=payload)
        assert res.status_code == 200
        data = res.json()
        assert "chunks" in data
        assert "diagnostics" in data
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/integration/test_retrieve_api.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'app.main')

- [ ] **Step 3: 实现 `qa_generator.py`、`routes.py` 与 `main.py`**

- `qa_generator.py`：流式调用 Codex-Bridge，返回带引用格式的回答；
- `routes.py`：编排解析、向量存储、双路召回、重排器与诊断输出；
- `main.py`：挂载 FastAPI，配置 lifespan 初始化数据库连接池与模型预热。

- [ ] **Step 4: 运行集成测试验证通过**

```powershell
pytest tests/integration/test_retrieve_api.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/app/ financial-knowledge-rag/tests/integration/test_retrieve_api.py
git commit -m "feat(rag): expose FastAPI endpoints and verify with end-to-end integration tests"
```

---

### Task 9: 离线超参数网格搜索实验套件与黄金评测集

**Files:**
- Create: `financial-knowledge-rag/data/golden/financial_qa_eval.jsonl`
- Create: `financial-knowledge-rag/scripts/run_benchmark.py`
- Create: `financial-knowledge-rag/README.md`
- Test: `financial-knowledge-rag/tests/unit/test_benchmark_runner.py`

**Interfaces:**
- Produces: `scripts.run_benchmark.run_grid_search(dataset_path)` -> 遍历 $K_{vec}, K_{bm25}, ChunkSize$，输出 Hit@K, MRR, NDCG 成绩单与最优参数推荐。

- [ ] **Step 1: 编写评测套件单测 `test_benchmark_runner.py`**

```python
# financial-knowledge-rag/tests/unit/test_benchmark_runner.py
from scripts.run_benchmark import calculate_mrr, calculate_ndcg

def test_ranking_metrics_calculation():
    # 命中首位
    assert calculate_mrr(rank=1) == 1.0
    # 命中第2位
    assert calculate_mrr(rank=2) == 0.5
    # 未命中
    assert calculate_mrr(rank=None) == 0.0
```

- [ ] **Step 2: 运行测试验证失败**

```powershell
pytest tests/unit/test_benchmark_runner.py -v
```
预期：FAIL (ModuleNotFoundError: No module named 'scripts.run_benchmark')

- [ ] **Step 3: 实现 `financial_qa_eval.jsonl` 与 `run_benchmark.py`**

- 准备 20+ 条涵盖研报、公告、指标定义的黄金问答样本；
- 实现 `run_benchmark.py`，输出精美的终端表格与 JSON 报告，清晰标示不同参数下的 Recall/Latency Pareto 曲线。
- 完善 `README.md`，记录一键启动与 API 使用示例。

- [ ] **Step 4: 运行单测验证通过**

```powershell
pytest tests/unit/test_benchmark_runner.py -v
```
预期：PASS

- [ ] **Step 5: 提交代码**

```bash
git add financial-knowledge-rag/data/ financial-knowledge-rag/scripts/ financial-knowledge-rag/README.md financial-knowledge-rag/tests/unit/test_benchmark_runner.py
git commit -m "feat(rag): add offline benchmark grid-search suite and golden evaluation dataset"
```

---

## 计划自查清单 (Self-Review Checklist)

1. **Spec 覆盖**：覆盖了多格式（Excel/Word/PDF/MD）、GPU OCR、VLM 流程图解析、表头挂顶切块、双路召回、GPU 神经重排器、全链路分段归因监控与离线网格搜索。
2. **占位符扫描**：无任何 TBD/TODO，所有步骤均包含具体文件路径、代码片段及可执行验证命令。
3. **类型一致性**：`ChunkDTO`, `RetrieveRequest`, `RetrieveResponse`, `DiagnosticsDTO` 在所有模块间定义完全一致。
