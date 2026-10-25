# Financial Knowledge RAG 微服务 (`financial-knowledge-rag`)

> **FinancialCopilot 平台核心金融投研知识库与 7 阶段全链路白盒化检索增强微服务**  
> 默认监听端口：`8820` | 底层架构：Python 3.11+ / FastAPI / PostgreSQL 16 `pgvector` / 本地 Codex-Bridge (`:9090`)

---

## 目录 (Table of Contents)

- [1. 系统定位与核心价值](#1-系统定位与核心价值)
- [2. 整体微服务架构与拓扑](#2-整体微服务架构与拓扑)
- [3. 重点：金融 PDF 深度解析与结构化入库全流程 (PDF Pipeline)](#3-重点金融-pdf-深度解析与结构化入库全流程-pdf-pipeline)
  - [3.1 PDF 解析入库全景流程图 (Mermaid)](#31-pdf-解析入库全景流程图-mermaid)
  - [3.2 阶段一：版面坐标几何去噪 (Layout Denoising)](#32-阶段一版面坐标几何去噪-layout-denoising)
  - [3.3 阶段二：表格区域动态遮罩 (Table Masking)](#33-阶段二表格区域动态遮罩-table-masking)
  - [3.4 阶段三：跨页表格智能缝合 (Cross-Page Table Stitching)](#34-阶段三跨页表格智能缝合-cross-page-table-stitching)
  - [3.5 阶段四：净值走势图与图表语义增强 (Chart Perception)](#35-阶段四净值走势图与图表语义增强-chart-perception)
  - [3.6 阶段五：人类真实阅读流重排 (Y0-Coordinate Sorting)](#36-阶段五人类真实阅读流重排-y0-coordinate-sorting)
  - [3.7 阶段六：顺应大纲与块元素切分 (Hierarchical Block Chunking)](#37-阶段六顺应大纲与块元素切分-hierarchical-block-chunking)
  - [3.8 阶段七：超长表格拆分与强制挂顶保护 (Header Pinning)](#38-阶段七超长表格拆分与强制挂顶保护-header-pinning)
  - [3.9 阶段八：金融实体提取与面包屑注入 (Entity & Breadcrumb Injection)](#39-阶段八金融实体提取与面包屑注入-entity--breadcrumb-injection)
  - [3.10 阶段九：1024 维向量编码与双路持久化 (Dual Storage)](#310-阶段九1024-维向量编码与双路持久化-dual-storage)
- [4. RAG 运行时 7 阶段全景链路 (Runtime Pipeline)](#4-rag-运行时-7-阶段全景链路-runtime-pipeline)
- [5. 四层十类认知分层治理规范 (Taxonomy)](#5-四层十类认知分层治理规范-taxonomy)
- [6. 效果评估中心与自动化测试 (Evaluation)](#6-效果评估中心与自动化测试-evaluation)
- [7. 快速启动与开发者操作指南 (Quick Start)](#7-快速启动与开发者操作指南-quick-start)
- [8. 核心 RESTful API 契约一览](#8-核心-restful-api-契约一览)

---

## 1. 系统定位与核心价值

在传统通用 RAG 系统中，面对专业金融研报（季报、年报、临时公告、产品概要）常出现三大致命缺陷：
1. **表格结构粉碎**：固定滑动窗口（如 500 tokens）强行切断财务报表，数字与列名脱钩；
2. **跨页断表断图**：跨页披露的行业配置表、前十大重仓表被割裂为不完整的碎片；
3. **事实幻觉无法溯源**：生成层模型可能“胡编乱造”不在切片中的数据，缺乏断言级合规审计。

`financial-knowledge-rag` 彻底重构了数据提取与检索链路：
- **以结构化知识切片为第一公民**：切片天然保留完整的 Markdown 表格、章节面包屑与股票/基金代码；
- **全流程白盒化**：提供前端 7 阶段耗时瀑布流（Parsing ➔ Chunking ➔ Embedding ➔ Hybrid Recall ➔ RRF Fusion ➔ Cross-Encoder Rerank ➔ Generation & Provenance Audit）；
- **LLM-as-a-Judge 与人工双轨质检**：内置四维量化裁判与 103 个全量切片毫秒级乐观更新质检台。

---

## 2. 整体微服务架构与拓扑

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    FinancialCopilot Multi-Agent System                          │
│        (FundAnalyzerAgent / StockAnalyzerAgent / ReportSynthesizerAgent)        │
└──────────────────────────────────────┬──────────────────────────────────────────┘
                                       │ HTTP / REST / SSE
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│              financial-knowledge-rag Service (FastAPI :8820)                    │
│                                                                                 │
│  ┌─────────────────────────┐  ┌───────────────────────┐  ┌───────────────────┐  │
│  │ 📂 Ingestion Engine     │  │ ⚡ Hybrid Retriever   │  │ 🛡️ Provenance     │  │
│  │ - PdfParser (PyMuPDF)   │  │ - Vector (Dense HNSW) │  │    Auditor        │  │
│  │ - pdfplumber 表格提取   │  │ - Fulltext (Sparse)   │  │ - 断言级忠实度    │  │
│  │ - HierarchicalSplitter  │  │ - RRF Rank Fusion     │  │ - 幻觉类型阻断    │  │
│  └─────────────────────────┘  └───────────────────────┘  └───────────────────┘  │
│               │                          │                         │            │
│               ▼                          ▼                         ▼            │
│  ┌─────────────────────────┐  ┌───────────────────────┐  ┌───────────────────┐  │
│  │ 🔍 Quality Inspector    │  │ 🧠 Cross-Encoder      │  │ ⚖️ Evaluation     │  │
│  │ - 表头保护/撕裂检测     │  │    Reranker           │  │    Center         │  │
│  │ - 乐观更新 UI (<5ms)    │  │ - BGE-Reranker-Large  │  │ - 8 黄金评测集    │  │
│  │ - 人工打标与快照回滚   │  │ - 业务代码/表格加权   │  │ - LLM-as-a-Judge  │  │
│  └─────────────────────────┘  └───────────────────────┘  └───────────────────┘  │
└──────────────────┬───────────────────────┬──────────────────────────┬───────────┘
                   │                       │                          │
                   ▼                       ▼                          ▼
     ┌────────────────────────┐ ┌────────────────────┐ ┌──────────────────────────┐
     │  PostgreSQL 16         │ │ Local Ollama       │ │ Local Codex-Bridge       │
     │  - Port: 15432         │ │ - Port: 11434      │ │ - Port: 9090             │
     │  - pgvector HNSW (1024)│ │ - qwen3-embedding  │ │ - gemini-3.8-flash-low   │
     │  - tsvector GIN 全文   │ │   (0.6b, 1024-dim) │ │ - OpenAI-Compatible API │
     └────────────────────────┘ └────────────────────┘ └──────────────────────────┘
```

---

## 3. 重点：金融 PDF 深度解析与结构化入库全流程 (PDF Pipeline)

处理金融 PDF 的核心在于**版面几何去噪、双引擎协同提取、跨页表格缝合与大纲树状切分**。以下是 `app/ingest/pdf_parser.py` 与 `app/ingest/splitter.py` 的完整实现流程。

### 3.1 PDF 解析入库全景流程图 (Mermaid)

```mermaid
flowchart TD
    A[📄 原始金融研报 / 公告 PDF] --> B[双引擎并行加载: pdfplumber + PyMuPDF]
    
    subgraph S1 [阶段 1-3: 几何去噪与表格缝合]
        B --> C1[pdfplumber 提取表格及精确 BBox]
        B --> C2[PyMuPDF 提取原始 Text Blocks 及 BBox]
        C1 --> D1[扫描跨页接续表格: Page N 底部 + Page N+1 顶部]
        D1 --> D2{满足缝合阈值且接续?}
        D2 -- 是 --> D3[缝合为完整二维矩阵并恢复断行单元格]
        D2 -- 否 --> D4[保持单页独立表格]
        D3 --> E1[转换为标准金融对齐 Markdown 表格]
        D4 --> E1
        C2 --> F1[版面去噪 A: 过滤页眉 y0 < 60]
        F1 --> F2[版面去噪 B: 过滤页码与页脚 y0 > 735]
        F2 --> F3[版面去噪 C: 表格区域遮罩 Masking<br/>若文字处于表格 BBox 内则剔除]
    end

    subgraph S2 [阶段 4-5: 走势图感知与真实阅读流排布]
        E1 --> G1[插入 TABLE 块元素]
        F3 --> G2[识别 H1 / H2 / H3 标题与 PARAGRAPH 块元素]
        B --> G3[识别 Section 3.2 历史净值走势图<br/>提取 A/C 类份额收益率与超额基准]
        G3 --> G4[插入 CHART 结构化走势图块]
        G1 --> H1[合并当前页所有元素: TABLE + TEXT + CHART]
        G2 --> H1
        G4 --> H1
        H1 --> H2[按真实人类阅读流 Y0 坐标升序排列]
    end

    subgraph S3 [阶段 6-8: 顺应大纲切块与表头挂顶保护]
        H2 --> I1[HierarchicalBlockSplitter 顺应大纲切块]
        I1 --> J1{遇到 H1 / H2 一二级大纲标题?}
        J1 -- 是 --> J2[强制刷新缓冲区 Flush, 建立语义硬边界]
        J1 -- 否 --> J3{遇到 TABLE / CHART 块?}
        J3 -- 是且长度 > 800字 --> K1[按数据行自适应拆分<br/>每个切块强制完整复制主表头 Header Pinning]
        J3 -- 否 --> K2[段落内容满 800 字自适应缓冲打包]
        J2 --> L1[标准切块 ParsedChunk 生成]
        K1 --> L1
        K2 --> L1
        L1 --> M1[正则表达式自动抽取 6 位股票与基金代码]
        M1 --> M2[注入面包屑导航: 【章节大纲: §X > Y.Z】(P.X-P.Y)]
    end

    subgraph S4 [阶段 9: 向量化与双路入库]
        M2 --> N1[本地 Ollama Qwen3-Embedding 生成 1024 维密集向量]
        N1 --> O1[(PostgreSQL 16 pgvector)]
        O1 --> P1[knowledge_chunks 表: HNSW 向量索引]
        O1 --> P2[knowledge_chunks 表: GIN 中文全文检索索引]
        O1 --> P3[knowledge_documents 表: 元数据与切片总数关联]
    end
```

---

### 3.2 阶段一：版面坐标几何去噪 (Layout Denoising)

在金融研报排版中，页眉通常包含动态文档名与公告标题，页脚包含日期与页码。若不加清洗直接切入切片，会导致向量检索被页眉关键词过度污染。

- **页眉清洗**：若文本框顶部坐标 $y_0 < 60$，且包含“报告”、“基金”、“季度”等运行页眉特征字，直接剔除；
- **页脚与页码清洗**：若文本框底部坐标 $y_0 > 735$，且符合纯数字、`第 X 页` 正则或固定编制日期尾注，直接剔除。

### 3.3 阶段二：表格区域动态遮罩 (Table Masking)

若同时使用 `pdfplumber` 抽取表格和 `PyMuPDF` 抽取文本，表格内部的数据单元格会被纯文本提取器二次读取为散落无序的文字流，导致严重的内容重复与语义混乱。

- **算法方案**：
  1. 先行由 `pdfplumber` 获取页面所有表格的精确外接矩形框 $\text{BBox} = (x_0, y_0, x_1, y_1)$；
  2. 遍历 `PyMuPDF` 文本块时，计算其中线垂直坐标 $y_{\text{mid}} = \frac{y_0 + y_1}{2}$；
  3. 若 $y_{\text{mid}}$ 落在任意表格外接框 $[y_0 - 4, y_1 + 4]$ 垂直范围内，则判定该文字块属于表格内容，**立即予以跳过（遮罩过滤）**；
  4. 表格内容全权交由表格专用提取器转换为规范 Markdown。

### 3.4 阶段三：跨页表格智能缝合 (Cross-Page Table Stitching)

公募基金定期报告（如《诺安成长混合 2026 年第 2 季度报告》）中的核心数据表格常跨越两页（例如 P.2~P.3 的“§2 基金产品概况”、P.9~P.10 的“§5.1 报告期末按行业分类的股票投资组合”）。

```
Page N (底端 y1 > 680):                  Page N+1 (顶端 y0 < 150):
┌─────────────────────────────┐         ┌─────────────────────────────┐
│ 制造费用与主营成本明细表    │         │ (无表头，直接接续数据行)    │
├──────────┬──────────────────┤         ├──────────┬──────────────────┤
│ 科目     │ 金额(万元)       │         │ 仓储物流 │ 2,410.50         │
├──────────┼──────────────────┤         ├──────────┼──────────────────┤
│ 原材料   │ 12,500.00        │         │ 合计     │ 18,910.50        │
└──────────┴──────────────────┘         └──────────┴──────────────────┘
```

- **缝合触发条件**：
  - 上一页表格底部坐标 $y_1 > 680$（紧贴页尾）；
  - 下一页存在首个表格且起始坐标 $y_0 < 150$（紧贴页眉）；
- **行截断修复机制**：
  - 检查接续页首行，若第 1 列为空白、第 2 列包含文字，说明上一页末行文字被物理截断；
  - 算法将下一页首行内容拼接回上一页末尾单元格，并剥离该残行，实现文字无损接续；
- **输出结果**：将跨越两页的数据矩阵合并为单张完整的二维数据表，并标记 `stitched: true, end_page: N+1`。

### 3.5 阶段四：净值走势图与图表语义增强 (Chart Perception)

PDF 中的折线走势图（如份额累计净值增长率走势）无法直接被文字解析器读取，但包含核心量化指标。
- 系统自动扫描图表版面定位，针对多类份额（如诺安成长 A 份额 `320007` 与 C 份额 `009581`）提取成立以来累计收益、业绩比较基准收益、超额收益差值等关键数字；
- 构造 `CHART` 专属图表块元素并注入对应基金代码，让图表数据能够被“收益率对比”、“走势图”等自然语言精准召回。

### 3.6 阶段五：人类真实阅读流重排 (Y0-Coordinate Sorting)

PDF 内部流数据存储次序往往乱序（例如先排版了底部的文本框，再绘制了顶部的标题）。
- 系统将当前页面抽取出的 `H1/H2/H3` 标题、`PARAGRAPH` 正文段落、`TABLE` Markdown 表格与 `CHART` 图表块汇聚为元素池；
- **强制按版面几何坐标 $y_0$ 升序排列**，确保在进入切块器前，所有元素严格贴合人类由上至下的阅读认知顺序。

### 3.7 阶段六：顺应大纲与块元素切分 (Hierarchical Block Chunking)

彻底废弃“无脑滑动窗口”切分，采用 `HierarchicalBlockSplitter` 策略：
- **大纲硬分界**：
  - 遇到一级标题（`H1`，如 `§5 投资组合报告`、`重要提示`）或二级标题（`H2`，如 `5.1 报告期末按行业分类的股票投资组合`），**强制 Flush 前置缓冲区**；
  - 绝不将上一章节的末尾段落与下一章节的标题混在一个切片中；
- **块元素原子性**：
  - 段落、表格与图表作为原子元素整体收集，在不超过预设容量（800 字符）的前提下平滑聚合。

### 3.8 阶段七：超长表格拆分与强制挂顶保护 (Header Pinning)

当某张财务表格极大（例如披露数百行明细超过 800 字）时，必须分块，但如果直接切断，第二块将丢失列名信息（如无法区分数字代表“公允价值”还是“占净值比例”）。

```markdown
<!-- 子切片 1 (带表头) -->
【章节大纲: §5 投资组合报告 > 5.3 期末按公允价值占基金资产净值比例大小排序的前十名股票投资明细】(P.10)
| 序号 | 股票代码 | 股票名称 | 数量(股) | 公允价值(元) | 占基金资产净值比例(%) |
|:---|:---|:---|:---|:---|:---|
| 1 | 688012 | 中微公司 | 8,240,119 | 1,250,123,000.00 | 8.58 |
| 2 | 002371 | 北方华创 | 3,110,240 | 1,180,500,000.00 | 8.10 |

<!-- 子切片 2 (强制复制挂顶表头，绝不遗漏语义) -->
【章节大纲: §5 投资组合报告 > 5.3 期末按公允价值占基金资产净值比例大小排序的前十名股票投资明细】(P.10)
| 序号 | 股票代码 | 股票名称 | 数量(股) | 公允价值(元) | 占基金资产净值比例(%) |
|:---|:---|:---|:---|:---|:---|
| 3 | 688072 | 拓荆科技 | 4,500,000 | 950,200,000.00 | 6.52 |
| 4 | 688126 | 沪硅产业 | 28,100,000 | 890,100,000.00 | 6.11 |
```

- **实现保障**：`_split_long_table` 方法始终将表格前两行（列名定义与对齐分隔线）提取为 `header_text`，在每个衍生子切片顶部强制复制注入。

### 3.9 阶段八：金融实体提取与面包屑注入 (Entity & Breadcrumb Injection)

- **股票/基金代码自动识别**：
  - 股票正则：`(?<!\d)(?:00|30|60|68)\d{4}(?!\d)`（精准匹配主板、创业板、科创板代码，排除普通金额数字）；
  - 基金正则：`(?<!\d)(?:00|01|15|16|50|51)\d{4}(?!\d)`；
  - 提取结果存储于元数据 `stock_codes` 与 `fund_codes` 字段；
- **面包屑大纲注入**：
  - 每个切片头部强制冠以当前所处的章节全路径与物理页码范围：  
    `【章节大纲: §5 投资组合报告 > 5.1 报告期末按行业分类的股票投资组合】(P.9-P.10)`。

### 3.10 阶段九：1024 维向量编码与双路持久化 (Dual Storage)

1. **向量生成**：调用本地 Ollama 驱动的 `qwen3-embedding:0.6b` 模型（端口 `11434`），将切块内容编码为 1024 维密集浮点向量；
2. **PostgreSQL 16 入库**：
   - 写入 `knowledge_chunks` 表；
   - 建立 **HNSW 索引**（`m=16, ef_construction=64, vector_cosine_ops`）用于高并发余弦相似度检索；
   - 建立 **tsvector GIN 索引** 用于 BM25 精确关键词检索；
3. **主文档元数据同步**：更新 `knowledge_documents` 表，记录该文档的入库切片总数、解析状态与更新时间戳。

---

## 4. RAG 运行时 7 阶段全景链路 (Runtime Pipeline)

当上层 Agent 或前端调用 `POST /api/knowledge/pipeline/trace` 时，系统触发 7 阶段结构化计算：

```
Stage 1: 文档解析 (Parsing) 
  └─ 解析文档元数据、去噪策略与跨页表格缝合状态
Stage 2: 顺应大纲切块 (Chunking)
  └─ 统计切块总数、表格数、走势图数与平均 Token 长度
Stage 3: 向量化编码 (Embedding)
  └─ 耗时约 40-50ms，1024 维密集向量生成
Stage 4: 双路并发粗召回 (Hybrid Retrieval)
  └─ 向量检索 (Top 30) + BM25 全文检索 (Top 30)，并发 gather 耗时约 15-20ms
Stage 5: RRF 倒数排名融合 (RRF Fusion)
  └─ Score = 1/(60 + r_vec) + 1/(60 + r_fulltext)，纯 CPU 耗时约 2-3ms
Stage 6: 神经重排与金融业务加权 (Neural Cross-Encoder)
  └─ BGE-Reranker 全注意力计算 + 股票代码匹配(+0.15) + 表格意图加权(+0.10)，截取 Top-K
Stage 7: 问答生成与断言级双轨溯源审计 (Generator & Provenance Auditor)
  └─ 本地 Codex-Bridge 生成事实回答，提取断言并精确对齐切片原文，输出忠诚度得分与幻觉阻断
```

---

## 5. 四层十类认知分层治理规范 (Taxonomy)

| 认知层级 (`CognitiveLayer`) | 核心分类全路径 (`CategoryPath`) | 涵盖资产与文档范例 | 表格密度 | 权威度基准 | 时间衰减半衰期 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **规范公理层 (`AXIOM`)** | `AXIOM.METRIC.RISK_ADJUSTED`<br>`AXIOM.ACCOUNTING.GAAP`<br>`AXIOM.REGULATION.COMPLIANCE` | 夏普比率/卡玛比率公式、CAS/IFRS 会计准则、基金双十比例限制红线 | 低 | 1.00 | **永久有效 (零衰减)** |
| **法定事实层 (`GROUND_TRUTH`)** | `GROUND_TRUTH.PERIODIC.STOCK_ANNUAL`<br>`GROUND_TRUTH.PERIODIC.STOCK_QUARTERLY`<br>`GROUND_TRUTH.PERIODIC.FUND_PERIODIC`<br>`GROUND_TRUTH.EVENT.ANNOUNCEMENT` | 上市公司定期报告、公募基金季报、拓荆科技估值调整公告、产品概要 | **极高 (强制保护)** | 1.00 | 报告期基准 (最新期优先) |
| **观点投研层 (`OPINION`)** | `OPINION.BROKER.TARGET_PRICE`<br>`OPINION.BROKER.INDUSTRY_TOPIC`<br>`OPINION.MEMO.EARNINGS_CALL` | 卖方券商研报、目标价预测、业绩说明会纪要、投决会备忘录 | 中 | 0.85 | 90 ~ 180 天 |
| **动态信号层 (`SIGNAL`)** | `SIGNAL.FLASH.MACRO_NEWS`<br>`SIGNAL.SENTIMENT.SOCIAL_BUZZ` | 宏观统计数据 (PMI/CPI)、盘中异动快讯、市场舆情情绪指数 | 低 | 0.65 | 24 ~ 72 小时 (30天归档) |

---

## 6. 效果评估中心与自动化测试 (Evaluation)

### 6.1 黄金评测集 (Golden Benchmark)
系统内置涵盖当前核心文档与公理的 8 道投研标准评测题：
- **跨期对比**：诺安成长 2026Q1 与 2026Q2 份额总额与前三大重仓股票变动对比；
- **估值调整**：对持有股票拓荆科技(688072)采用行业指数收益法调整生效日与依据；
- **资料概要**：管理费率(1.2%)、托管费率(0.2%)与股票资产投资比例限制(60%-95%)；
- **规范公理**：双十投资比例限制红线；夏普比率 Sharpe Ratio 年化计算公式。

### 6.2 评测指标与横向跑分
提供横向对比矩阵接口：
- `Recall@5`（召回率）、`Precision@5`（精确率）、`Hit@5`（命中率）、`MRR`（平均倒数排名）、`NDCG@5`（折损累计增益）、平均耗时。
- 支持三大策略同场竞技：**BGE 神经重排 (阈值 0.35)** vs **BGE 宽进策略 (阈值 0.25)** vs **RRF 算法融合 (纯 CPU 免显卡)**。

### 6.3 LLM-as-a-Judge 四维智能裁判
由本地 Codex-Bridge (`gemini-3.8-flash-low`) 驱动客观打分（1~5 分）：
1. **答案正确性 (Correctness)**
2. **回答完备性 (Completeness)**
3. **引用一致性 (Citation Consistency)**
4. **无幻觉程度 (No Hallucination)**
5. **综合结论裁定**：`PASS` (放行) / `REVIEW` (人工复审) / `FAIL` (驳回)。

---

## 7. 快速启动与开发者操作指南 (Quick Start)

### 7.1 启动基础设施
```powershell
# 1. 本地 Codex-Bridge (端口 9090)
cd D:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\codex-bridge
python -m uvicorn app.main:app --host 127.0.0.1 --port 9090

# 2. 本地 Ollama Embedding (端口 11434)
ollama run qwen3-embedding:0.6b

# 3. PostgreSQL 16 pgvector (端口 15432)
# 确保数据库容器运行且已执行 init_db.py
```

### 7.2 运行核心文档全量结构化入库
```powershell
cd D:\BaiduSyncdisk\IdeaProjects\FinancialCopilot\financial-knowledge-rag
python scripts/ingest_all_documents.py
```
> 输出结果将完整入库 4 份核心文档（总计 103 个切片、31 张财务表格、4 个走势图）。

### 7.3 启动微服务与可视化观测台
```powershell
python -m uvicorn app.main:app --host 127.0.0.1 --port 8820 --reload
```
- **可视化工作台**：打开浏览器访问 `http://127.0.0.1:8820/static/index.html`
- **Swagger API 文档**：访问 `http://127.0.0.1:8820/docs`
- **健康检查**：`curl http://127.0.0.1:8820/health`

### 7.4 执行自动化测试套件
```powershell
# 运行全部单元测试
pytest tests/unit/ -v

# 针对性运行前端工作台契约测试
pytest tests/unit/test_frontend_workbench.py -v
```

---

## 8. 核心 RESTful API 契约一览

| 模块 | 方法与路径 | 说明 | 入参关键字段 | 返回关键字段 |
| :--- | :--- | :--- | :--- | :--- |
| **文档管理** | `GET /api/knowledge/documents` | 获取已入库文档列表 | 无 | `id, title, chunk_count, status` |
| **切片质检** | `GET /api/knowledge/documents/{doc_id}/chunks` | 拉取切片全量列表及质检分析 | `doc_id` (`"all"` 表示全库) | 103 个切片明细、`quality_analysis` |
| **切片打标** | `POST /api/knowledge/chunks/{chunk_id}/verify` | 人工核验状态打标 | `status, notes, verified_by` | 更新后状态快照 |
| **全景观测** | `POST /api/knowledge/pipeline/trace` | 7 阶段全流程白盒化追踪 | `query, document_id, top_k` | `timings`, 7 个 Stage 详尽数据与审计 |
| **基准评测** | `GET /api/knowledge/evaluation/dataset` | 获取 8 道黄金评测题目 | 无 | 题目列表、GT 切片、标准答案 |
| **跑分矩阵** | `POST /api/knowledge/evaluation/run_benchmark` | 策略横向基准跑分对比 | `strategy, top_k, threshold` | `comparison_matrix, sample_details` |
| **智能裁判** | `POST /api/knowledge/evaluation/llm_judge` | LLM-as-a-Judge 四维打分 | `query, answer, context_chunks` | 4 维量化得分(1-5)、理由、裁定结论 |
| **人工看板** | `GET /api/knowledge/evaluation/human_reviews` | 获取人工质检历史归档 | 无 | 历史评审列表、聚合统计 |
| **人工提交** | `POST /api/knowledge/evaluation/human_review` | 提交人工核验记录 | `query, answer, score, notes` | 保存结果与更新后的看板数据 |
