# Financial Knowledge RAG 微服务 (`financial-knowledge-rag`)

`financial-knowledge-rag` 是 `FinancialCopilot` 金融投研多智能体平台的核心知识检索微服务（默认监听端口 **8820**）。

与传统通用 RAG 直接返回冗长合成文字不同，本微服务将 **Top-K 精排结构化知识片段（含完整表格、溯源路径与置信度打分）作为第一公民**，专为下游 AgentCore (`FundAnalyzerAgent`, `StockAnalyzerAgent`, `ReportSynthesizerAgent`) 提供高置信、低幻觉且包含完整表格数据的上下文支撑。

---

## 1. 核心架构与技术栈

- **服务框架**：Python 3.11+ / FastAPI / Uvicorn（端口 `8820`）
- **向量与全文存储**：PostgreSQL 16 + `pgvector`（端口 `15432`，HNSW 余弦距离索引 + tsvector BM25 全文索引）
- **本地 Embedding 驱动**：本地 Ollama 驱动 `qwen3-embedding:0.6b`（端口 `11434`，1024 维）
- **本地 LLM & 多模态 VLM**：遵循平台全局规范，优先使用本地 `codex-bridge` (`http://127.0.0.1:9090/v1`，Key `local-agy`，模型 `gemini-3.8-flash-low`)
- **GPU 神经重排器**：本地 CUDA 加速 `BAAI/bge-reranker-large`（Cross-Encoder 全注意力精排，无显卡自动降级为 CPU 或 RRF 算法）
- **解析与表格保护**：`TableAwareSplitter`（跨切片表头强制挂顶保护）、`PyMuPDF` + `pdfplumber` + GPU OCR、`openpyxl`（Excel 复合多级表头对齐）。

---

## 2. 金融知识库四层十类认知分层治理矩阵 (Taxonomy Matrix)

系统彻底解耦公理、事实、观点与时效信号，支持跨越股票与基金维度的细粒度元数据过滤：

| 认知层级 (`CognitiveLayer`) | 核心分类全路径 (`CategoryPath`) | 涵盖资产与文档范例 | 表格密度 | 权威度基准 | 时间衰减半衰期 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **规范公理层 (`AXIOM`)** | `AXIOM.METRIC.RISK_ADJUSTED`<br>`AXIOM.ACCOUNTING.GAAP`<br>`AXIOM.REGULATION.COMPLIANCE` | 夏普比率/卡玛比率公式、CAS/IFRS 会计准则、基金双十比例限制红线 | 低 | 1.00 | **永久有效 (零衰减)** |
| **法定事实层 (`GROUND_TRUTH`)** | `GROUND_TRUTH.PERIODIC.STOCK_ANNUAL`<br>`GROUND_TRUTH.PERIODIC.STOCK_QUARTERLY`<br>`GROUND_TRUTH.PERIODIC.FUND_QUARTERLY`<br>`GROUND_TRUTH.EVENT.ANNOUNCEMENT` | 上市公司年报/中报/季报、公募基金定期运作报告、重大临时分红派息公告 | **极高 (强制保护)** | 1.00 | 报告期基准 (最新期优先) |
| **观点投研层 (`OPINION`)** | `OPINION.BROKER.TARGET_PRICE`<br>`OPINION.BROKER.INDUSTRY_TOPIC`<br>`OPINION.MEMO.EARNINGS_CALL`<br>`OPINION.MEMO.INTERNAL_RESEARCH` | 卖方券商深度研报、盈利预测与目标价、业绩说明会问答纪要、投决会备忘录 | 中 | 0.85 | 90 ~ 180 天 |
| **动态信号层 (`SIGNAL`)** | `SIGNAL.FLASH.MACRO_NEWS`<br>`SIGNAL.SENTIMENT.SOCIAL_BUZZ` | 宏观统计数据发布 (PMI/CPI)、突发盘中异动快讯、市场股吧舆情情绪指数 | 低 | 0.65 | 24 ~ 72 小时 (30天归档) |

---

## 3. 本地启动与运行指南

### 3.1 基础设施依赖就绪检查
1. **PostgreSQL 16 + pgvector** (已在 15432 端口运行)：
   ```powershell
   # 初始化表结构与向量索引
   python scripts/init_db.py
   ```
2. **本地 Ollama Embedding** (端口 11434)：
   ```powershell
   ollama pull qwen3-embedding:0.6b
   ```
3. **本地 Codex-Bridge** (端口 9090)：
   ```powershell
   cd D:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\codex-bridge
   python -m uvicorn app.main:app --host 127.0.0.1 --port 9090
   ```

### 3.2 启动 RAG 微服务
```powershell
cd D:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\financial-knowledge-rag
python -m uvicorn app.main:app --host 127.0.0.1 --port 8820 --reload
```
- **健康检查**：`GET http://127.0.0.1:8820/health`
- **交互式文档**：`http://127.0.0.1:8820/docs`

---

## 4. 批量文档摄取脚本与使用

支持一键扫描指定本地文件夹下所有 `.pdf`, `.xlsx`, `.xls`, `.docx`, `.md`, `.txt` 文件：

```powershell
# 批量解析指定目录下的所有研报与公告并入库
python scripts/ingest_folder.py `
  --input-dir "D:/BaiduSyncdisk/IdeaProjects/FinancialCopilot/data/samples" `
  --cognitive-layer "GROUND_TRUTH" `
  --category-path "GROUND_TRUTH.PERIODIC" `
  --institution "中金公司"
```

---

## 5. API 核心接口契约

### 5.1 结构化 Top-K 片段精准检索 (`POST /api/knowledge/retrieve`)
```json
{
  "query": "宁德时代2024年三季报动力电池毛利率与出货量预期",
  "top_k": 5,
  "score_threshold": 0.35,
  "filters": {
    "stock_codes": ["300750"],
    "cognitive_layers": ["GROUND_TRUTH", "OPINION"]
  },
  "rerank_strategy": "neural",
  "explain": true
}
```

**响应结构**：
```json
{
  "query": "宁德时代2024年三季报动力电池毛利率与出货量预期",
  "total_candidates": 38,
  "chunks": [
    {
      "chunk_id": "chunk_doc8821_p3_01",
      "document_id": "doc_8821",
      "document_title": "宁德时代2024Q3业绩点评",
      "cognitive_layer": "GROUND_TRUTH",
      "category_path": "GROUND_TRUTH.PERIODIC.STOCK_QUARTERLY",
      "institution": "中金公司",
      "page_number": 3,
      "content": "2024前三季度毛利率达到 28.2%...\n\n| 指标 | 2024Q3 | 环比 |\n| 毛利率 | 28.2% | +0.5% |",
      "score": 0.892,
      "recall_sources": ["VECTOR", "FULLTEXT"],
      "has_table": true,
      "metadata": {
        "stock_codes": ["300750"]
      }
    }
  ],
  "diagnostics": {
    "latency_breakdown_ms": {
      "hybrid_recall_latency": 18.2,
      "rerank_latency": 22.4,
      "total_latency": 40.6
    },
    "recall_funnel": {
      "vector_total_recalled": 30,
      "fulltext_total_recalled": 25,
      "union_candidates": 38
    },
    "warnings": []
  }
}
```

### 5.2 端到端流式生成与引用溯源 (`POST /api/knowledge/query/stream`)
以 Server-Sent Events (SSE) 协议输出回答，末尾自动追加 `[1] 《文档标题》- 第3页` 引用列表。

---

## 6. 离线超参数网格搜索实验

内置 20+ 条覆盖股票、基金、公式公理的黄金评测数据集：
```powershell
python scripts/run_benchmark.py --dataset data/golden/financial_qa_eval.jsonl
```
自动计算并输出 `Hit@5`、`MRR`、`NDCG@5` 对比分析报表。
