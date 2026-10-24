# 金融知识库 RAG 服务 (financial-knowledge-rag) 详细设计规格说明书

- **文档状态**：已评审并确认 (Approved)
- **创建日期**：2026-10-24
- **服务名称**：`financial-knowledge-rag`
- **默认监听端口**：`8820`
- **技术栈**：Python 3.11+ / FastAPI / PostgreSQL (pgvector) / 本地 Codex-Bridge (Gemini Flash) / 本地 Ollama Embedding / 本地 GPU 神经重排器 (Cross-Encoder)

---

## 1. 系统概述与核心服务边界

### 1.1 业务背景与系统定位
在 `FinancialCopilot` 金融投研多智能体平台中，下游各专业 Agent（如 `FundAnalyzerAgent`、`StockAnalyzerAgent`、`ReportSynthesizerAgent`）在进行宏观、行业、个股与基金深度研报分析时，需要自主阅读并推理原始知识片段，而非直接被动接收由固定提示词生成的死板回答。

`financial-knowledge-rag` 是一个独立的 Python 高性能微服务，其核心使命是：
1. **多源异构金融文档摄取**：深度支持 PDF（原生与扫描件）、Excel、Word、Markdown，通过**表格结构保持**与**多模态流程图解构**保证数据完整性；
2. **高质量 Top-K 片段交付（第一公民）**：采用「稠密向量 + 稀疏全文检索」双路召回，并由本地 GPU 驱动的 **Neural Cross-Encoder（重排模型）** 完成精细化打分排序，为 AgentCore 交付富含元数据的 Top-K 结构化知识片段；
3. **白盒化归因监控**：全链路透视分析切块、Embedding、召回与重排各阶段的精度损耗，支持离线超参数网格搜索与运行时候选池截断告警。

### 1.2 外部交互与拓扑边界
```
┌────────────────────────────────────────────────────────┐
│ Java 核心层 (copilot-agent-core / copilot-mcp-service)  │
└───────────────────────────┬────────────────────────────┘
                            │ HTTP POST (Top-K Chunks / Filters)
                            ▼
┌────────────────────────────────────────────────────────┐
│         financial-knowledge-rag (Port 8820)            │
│  ┌──────────────────┐  ┌────────────────────────────┐  │
│  │ Ingestion Engine │  │ Hybrid Retrieval & Rerank  │  │
│  └────────┬─────────┘  └─────────────┬──────────────┘  │
│           │                          │                 │
│           │ (写入向量与全文)         │ (双路召回与融合)│
└───────────┼──────────────────────────┼─────────────────┘
            ▼                          ▼
┌────────────────────────────────────────────────────────┐
│  基础设施与本地模型驱动:                                │
│  - PostgreSQL 16 + pgvector (127.0.0.1:15432)          │
│  - 本地 Codex-Bridge LLM & VLM (127.0.0.1:9090)        │
│  - 本地 Ollama Embedding (127.0.0.1:11434)             │
│  - 本地 GPU 神经重排器 (CUDA: bge-reranker-large)       │
└────────────────────────────────────────────────────────┘
```

---

## 2. API 接口契约设计

### 2.1 结构化 Top-K 片段精准检索（核心接口）
* **接口路径**：`POST /api/knowledge/retrieve`
* **接口职责**：为下游 Agent 或 MCP Tool 检索最相关、高置信度且表格完整的知识切片。

#### 请求体契约 (`RetrieveRequest`)
```json
{
  "query": "宁德时代2024年三季报动力电池毛利率与出货量预期",
  "top_k": 5,
  "score_threshold": 0.35,
  "filters": {
    "stock_codes": ["300750"],
    "doc_types": ["RESEARCH_REPORT", "ANNOUNCEMENT"],
    "institutions": ["中金公司", "华泰证券"],
    "publish_date_start": "2024-01-01",
    "publish_date_end": "2024-10-24"
  },
  "rerank_strategy": "neural",
  "explain": true
}
```

#### 响应体契约 (`RetrieveResponse`)
```json
{
  "query": "宁德时代2024年三季报动力电池毛利率与出货量预期",
  "total_candidates": 42,
  "chunks": [
    {
      "chunk_id": "chunk_doc8821_p3_01",
      "document_id": "doc_8821",
      "document_title": "宁德时代2024Q3业绩点评：盈利能力持续韧性，海外份额扩张",
      "doc_type": "RESEARCH_REPORT",
      "institution": "中金公司",
      "author": "张三, 李四",
      "publish_date": "2024-10-20",
      "page_number": 3,
      "section_title": "一、三季度盈利拆解与出货指引",
      "content": "2024年前三季度动力电池出货量约260GWh...单瓦时净利润保持在0.08-0.09元区间，毛利率环比提升0.5pct达到28.2%...\n\n| 指标 | 2024Q3 | 环比 | 同比 |\n| 毛利率 | 28.2% | +0.5% | +1.2% |",
      "score": 0.892,
      "recall_sources": ["VECTOR", "FULLTEXT"],
      "has_table": true,
      "metadata": {
        "stock_codes": ["300750"],
        "table_headers": ["指标", "2024Q3", "环比", "同比"]
      }
    }
  ],
  "diagnostics": {
    "latency_breakdown_ms": {
      "embedding_latency": 15.4,
      "vector_query_latency": 11.2,
      "fulltext_query_latency": 7.8,
      "rerank_latency": 26.5,
      "total_latency": 60.9
    },
    "recall_funnel": {
      "vector_total_recalled": 30,
      "fulltext_total_recalled": 30,
      "union_candidates": 42
    },
    "warnings": []
  }
}
```

### 2.2 文档上传与异步入库接口
* **接口路径**：`POST /api/knowledge/documents/upload`
* **Content-Type**：`multipart/form-data`
* **表单参数**：
  * `file`: 二进制文件（支持 `.pdf`, `.xlsx`, `.xls`, `.docx`, `.md`, `.json`, `.txt`）
  * `doc_type`: `RESEARCH_REPORT` | `ANNOUNCEMENT` | `METRIC_EXPLAIN` | `MACRO_NOTE`
  * `institution`: 机构名称（可选，若未指定则由解析器正则提取）
  * `publish_date`: 发布日期（可选，默认提取文档日期或当前日期）
* **响应结果**：返回 `doc_id` 与异步解析任务状态 `status: "PROCESSING"`。

### 2.3 文档解析进度状态查询
* **接口路径**：`GET /api/knowledge/documents/{doc_id}/status`
* **响应结果**：`{ "document_id": "...", "status": "READY", "chunk_count": 36, "error_message": null }`

### 2.4 端到端问答与流式接口（调试/独立运行备用）
* **接口路径**：`POST /api/knowledge/query/stream`
* **协议**：`text/event-stream` (SSE)，基于检索到的 Top-K 片段直接驱动本地 Codex-Bridge LLM 进行逐字流式问答，并在末尾输出引用来源（Citations）。

---


## 3. 金融知识库分层分级治理模型 (Taxonomy & Governance Matrix)

### 3.1 四层十类认知分类树
系统采用「四层认知本体模型」彻底解耦事实与观点，并通过「实体穿透标签」跨越基金、股票与宏观：

1. **规范公理层 (`AXIOM`)**：
   * `AXIOM_METRIC`：金融指标与量化公式（夏普比率、卡玛比率、Sortino、VaR、ROE、自由现金流）；
   * `AXIOM_ACCOUNTING`：财务会计准则（CAS/IFRS 收入确认、研发支出资本化口径）；
   * `AXIOM_REGULATION`：监管风控法规（基金双十原则、减持新规、信息披露合规红线）。
   * *特点*：绝对常青基准，零时间衰减，永久保存。
2. **法定事实层 (`GROUND_TRUTH`)**：
   * `FACT_PERIODIC`：上市公司年报/中报/季报、基金定期运作报告、法定招股书（表格最密集，审计事实）；
   * `FACT_EVENT`：重大事件临时公告（资本运作、分红派息、高管变更、基金经理变动、立案处罚）。
   * *特点*：不可篡改事实，以报告期（Point-in-Time）为基准，最新期覆盖但保留历史回溯。
3. **观点投研层 (`OPINION`)**：
   * `OPINION_BROKER`：卖方券商研报（个股深度、行业专题、宏观大势、盈利预测与目标价）；
   * `OPINION_MEMO`：业绩说明会问答纪要 (Earnings Call)、专家访谈与调研纪要、内部投决会备忘录。
   * *特点*：主观视角与预测性，半衰期 90~180 天，随新财报发布自动折旧。
4. **动态信号层 (`SIGNAL`)**：
   * `SIGNAL_FLASH`：财经通讯社快讯、突发盘中异动、宏观数据发布（PMI/CPI/非农）；
   * `SIGNAL_SENTIMENT`：市场舆情监测与热帖分析。
   * *特点*：高时效流数据，半衰期 24~72 小时，30 天后自动归档至冷库。

### 3.2 存储结构与数据库模式设计 (PostgreSQL + pgvector)

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. 知识库主文档表
CREATE TABLE IF NOT EXISTS knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    cognitive_layer VARCHAR(32) NOT NULL,        -- AXIOM, GROUND_TRUTH, OPINION, SIGNAL
    category_path VARCHAR(128) NOT NULL,        -- 例: GROUND_TRUTH.PERIODIC.STOCK_ANNUAL
    institution VARCHAR(128),
    author VARCHAR(128),
    publish_date DATE,
    report_period VARCHAR(32),                   -- 例: 2024Q3, 2024-ANNUAL
    is_latest_period BOOLEAN DEFAULT FALSE,      -- 是否最新报告期
    authority_score FLOAT DEFAULT 1.0,           -- 权威度打分: 1.0(法定), 0.85(券商), 0.65(快讯)
    file_path VARCHAR(512),
    file_hash VARCHAR(64) UNIQUE,
    chunk_count INT DEFAULT 0,
    status VARCHAR(32) DEFAULT 'PROCESSING',     -- PROCESSING, READY, FAILED, SUPERSEDED
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. 知识切片与向量索引表
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    chunk_index INT NOT NULL,
    page_number INT,
    section_title VARCHAR(256),
    content TEXT NOT NULL,
    content_length INT NOT NULL,
    embedding vector(1024),
    cognitive_layer VARCHAR(32) NOT NULL,        -- AXIOM, GROUND_TRUTH, OPINION, SIGNAL
    category_path VARCHAR(128) NOT NULL,        -- 例: GROUND_TRUTH.PERIODIC.STOCK_ANNUAL
    stock_codes TEXT[] DEFAULT '{}',             -- 关联股票代码: ['300750']
    fund_codes TEXT[] DEFAULT '{}',              -- 关联基金代码: ['005827']
    metadata JSONB DEFAULT '{}'::jsonb,          -- 表格标记 has_table, 流程图 mermaid 等
    tsv_content tsvector,                        -- 全文检索向量
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 建立复合索引
CREATE INDEX IF NOT EXISTS idx_chunks_doc_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_category ON knowledge_chunks(category_path);
CREATE INDEX IF NOT EXISTS idx_chunks_stocks ON knowledge_chunks USING GIN(stock_codes);
CREATE INDEX IF NOT EXISTS idx_chunks_funds ON knowledge_chunks USING GIN(fund_codes);
CREATE INDEX IF NOT EXISTS idx_chunks_metadata ON knowledge_chunks USING GIN(metadata);
CREATE INDEX IF NOT EXISTS idx_chunks_tsv ON knowledge_chunks USING GIN(tsv_content);

-- HNSW 余弦相似度向量索引
CREATE INDEX IF NOT EXISTS idx_chunks_vector ON knowledge_chunks 
USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
```

---

## 4. 多源异构文档解析与表格感知切块流水线

### 4.1 四类异构文档解析器
1. **Excel 解析器 (`ExcelParser`)**：
   * 基于 `openpyxl` 逐 Sheet 读取资产负债表、利润表、现金流量表；
   * 对多级表头和合并单元格自动执行“向前填充”与扁平化层级拼接，输出语义自包含的 Markdown 表格。
2. **Word 解析器 (`WordParser`)**：
   * 基于 `python-docx` 抽取大纲标题树（`Heading 1~4`），记录小节路径面包屑；
   * 独立抽取嵌入式表格并转换为 Markdown 结构。
3. **PDF 双轨解析器 (`PdfParser` + `OcrEngine`)**：
   * **探针分流**：使用 `PyMuPDF` 探测页面文字流。若文字字数 < 30，判定为扫描件或图像页，分流至 OCR 引擎；
   * **原生页**：使用 `pdfplumber` 抽取原生表格坐标与文字段落；
   * **扫描页 / 图片**：调用 GPU 加速的 OCR 引擎（`PaddleOCR` 或 `RapidOCR-GPU`），毫秒级完成文字与表格边界识别。
   * **内存安全保证**：采用生成器（Generator）逐页流式处理，无论 1.3MB 研报还是 50MB 招股书，处理完一页立即释放位图显存/内存，常驻内存 < 300MB。
4. **复杂流程图与架构图的多模态 VLM 解析 (`VlmAnalyzer`)**：
   * 自动探测页面中长宽比大、非文本且带箭头的图表区域；
   * 异步调用本地 Codex-Bridge 的 `gemini-3.8-flash-low` 视觉端点；
   * 将流程图、股权架构穿透图转化为“参与主体清单 + 箭头流向 + Mermaid 语法描述”，以图表 Chunk 形式注入索引。

### 4.2 表格感知切块器 (`TableAwareSplitter`)
* **切分窗口**：目标字符数 600~800 字符（约 400~500 Tokens），滑动重叠 100 字符；
* **表头强制挂顶保护（Header Duplication）**：
  * 当大表格被跨 Chunk 切分时，每个切片顶部强制复制插入主表头行；
  * 表格正文严禁跨行撕裂单元格数据，确保每一行数值都完整对齐列名。

---

## 5. 混合检索与双阶段重排算法

### 5.1 检索漏斗与超参数设计依据

| 环节 | 候选深度 / 参数 | 选型依据与数学/工程机理 |
| :--- | :--- | :--- |
| **向量粗召回** | $K_{vec} = 30$ | C-MTEB 基准显示 $K=30$ 时金融长尾语义覆盖率已达 94%~96%，从 30 增加到 100 召回率提升不足 2%，但后续 Cross-Encoder 算力消耗暴增 300%。 |
| **全文粗召回** | $K_{bm25} = 30$ | 针对股票代码（如 `300750`）、公告编号、会计科目做确定性硬匹配，弥补向量模型数字模糊盲区。 |
| **融合候选池** | $N_{pool} = 40 \sim 50$ | 向量与全文召回并集去重后的深度，正好适配 GPU 单批次 Batch 推理。 |
| **神经重排器** | `bge-reranker-large` (GPU) | Cross-Encoder 全注意力机制联合计算 `(Query, Chunk)`，相关性判定精度显著超越双塔余弦相似度。GPU Batch 推理仅需 20~30ms。 |
| **最终交付** | $K_{final} = 5$ | 约 3000~4000 字符，满足下游 AgentScope 最优信息密度，防止大模型陷入“迷失中间”（Lost in the Middle）。 |

### 5.2 精排流程与业务规则加权
1. **Reranker 评分**：输入候选列表，输出 `[0.0, 1.0]` 区间的相关度打分；
2. **股票代码硬匹配加权**：若查询词包含股票代码，且 Chunk 元数据中完全命中该代码，得分加权 $+0.15$；
3. **表格数据意图加权**：若查询词命中“财务、指标、出货量、拆解、表格”，且 Chunk 的 `has_table == true`，得分加权 $+0.10$；
4. **低分截断**：低于 `score_threshold`（默认 0.35）的无关片段自动截断。

---

## 6. 全链路分段归因监控与评测诊断系统

### 6.1 四步根因定位决策树 (Root Cause Decision Tree)
当在测试集或生产中发现检索精度不足时，通过诊断透视自动分类归因：

```
                    [ 目标知识片段未被最终 Top-5 命中 ]
                                   │
                 ┌─────────────────┴─────────────────┐
                 ▼                                   ▼
          【候选池中完全不存在】               【候选池中存在但排位落后】
                 │                                   │
      ┌──────────┴──────────┐             ┌──────────┴──────────┐
      ▼                     ▼             ▼                     ▼
【阶段 1: 切块阶段损耗】 【阶段 2: 向量表征局限】 【阶段 3: 粗召回截断】 【阶段 4: 重排策略失真】
- 表格被跨块打散破坏   - 专业黑话向量距离远  - 目标排在第 35 名    - Reranker 误判
- 页面扫描 OCR 乱码    - 需补充专有词表映射  - 触发候选池截断告警  - 业务加权系数失衡
```

### 6.2 运行时候选池饱和度告警 (Pool Saturation Alert)
在 `RetrieveResponse.diagnostics` 中，若最终进入 Top-5 的高分 Chunk 在粗召回阶段排名位于候选集末梢（例如处于第 28~30 位），系统自动记录警告：
> `[Warning: Candidate Pool Saturation] 目标结果来自粗召回末梢 (Rank 29/30)，表明 K_vec=30 偏紧，建议上调至 40 防止长尾漏召回。`

### 6.3 离线超参数网格搜索实验器 (`scripts/run_benchmark.py`)
内置自动化评估套件，支持在黄金测试集上自动化搜索最优参数：
* 评估指标：`Hit@5`, `Hit@10`, `MRR` (Mean Reciprocal Rank), `NDCG@5`, `Latency_ms`；
* 遍历搜索空间：$K_{vec} \in [15, 30, 50]$, $ChunkSize \in [400, 600, 800]$, $Threshold \in [0.25, 0.35, 0.45]$；
* 自动生成对比报表与 Pareto 权衡曲线。

---

## 7. 项目工程目录与模块规划

服务源码置于顶级工程目录 `financial-knowledge-rag/`：

```
financial-knowledge-rag/
├── pyproject.toml                     # 项目依赖与打包规范
├── README.md                          # 部署与启动指南
├── .env.example                       # 环境变量模版
│
├── app/
│   ├── __init__.py
│   ├── main.py                        # FastAPI 入口、生命周期与异常全局挂载
│   ├── config.py                      # Pydantic 强类型配置
│   │
│   ├── api/                           # 路由与 DTO
│   │   ├── __init__.py
│   │   ├── routes.py                  # API 路由定义
│   │   └── models.py                  # 请求响应模型定义
│   │
│   ├── ingest/                        # 解析与切块引擎
│   │   ├── __init__.py
│   │   ├── parser_registry.py         # 格式分发工厂
│   │   ├── pdf_parser.py              # PDF 原生与流式页面提取
│   │   ├── ocr_engine.py              # GPU OCR 引擎与 CPU 降级
│   │   ├── vlm_analyzer.py            # Codex-Bridge Gemini 流程图视觉转译
│   │   ├── excel_parser.py            # Excel 复合表头对齐解析
│   │   ├── docx_parser.py             # Word 结构大纲解析
│   │   ├── markdown_parser.py         # Markdown 解析
│   │   └── splitter.py                # TableAwareSplitter: 表头强制挂顶切块
│   │
│   ├── retrieval/                     # 检索与重排核心
│   │   ├── __init__.py
│   │   ├── embedding_client.py        # Ollama / 本地 Embedding 批处理客户端
│   │   ├── pgvector_store.py          # PostgreSQL 向量与全文异步仓储
│   │   ├── hybrid_retriever.py        # 向量+全文双路并发召回引擎
│   │   └── reranker.py                # GPU Neural Cross-Encoder 与业务加权
│   │
│   ├── diagnostics/                   # 归因透视与可观测性
│   │   ├── __init__.py
│   │   ├── tracer.py                  # 耗时统计与 OTel Trace 挂接
│   │   └── explainer.py               # 位移分析与四步归因决策树
│   │
│   └── generator/                     # 解耦的端到端问答备用模块
│       ├── __init__.py
│       └── qa_generator.py            # 引用溯源与流式生成
│
├── data/
│   ├── samples/                       # 各种格式样本文档供测试
│   └── golden/                        # 黄金评测问答集
│
├── scripts/
│   ├── init_db.py                     # 数据库与向量索引初始化脚本
│   └── run_benchmark.py               # 自动化精度与网格搜索实验脚本
│
└── tests/
    ├── conftest.py                    # Pytest 夹具
    ├── unit/
    │   ├── test_parsers.py            # 多格式解析与表头保护单测
    │   ├── test_ocr_and_vlm.py        # OCR 与多模态流程图转译单测
    │   ├── test_hybrid_retrieval.py   # 双路召回与重排单测
    │   └── test_diagnostics.py        # 诊断透视与饱和度告警单测
    └── integration/
        └── test_retrieve_api.py       # API 端到端集成测试
```

---

## 8. 容错韧性与降级机制 (Resilience)

1. **GPU 显存保护与降级**：若未检测到 CUDA 设备或显存不足，`OcrEngine` 与 `Reranker` 自动优雅降级为多核 CPU 运行或算法级 RRF 融合，绝不导致服务崩溃；
2. **VLM 流程图解析超时降级**：若调用本地 Codex-Bridge 多模态接口超时，自动降级为保留底层 OCR 文本片段并打上 `vlm_degraded: true` 标记；
3. **单路召回兜底**：若 PostgreSQL 全文分词未命中任何词条，稠密向量路自动独立承载；若 Embedding 服务短暂抖动，全文检索路自动顶上，保障服务可用性。
