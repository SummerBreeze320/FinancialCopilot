# 金融投研 RAG 全场景可视化观测与评测工作台详细设计规格说明书 (RAG Workbench Design Spec)

- **文档状态**：已评审并确认 (Approved)
- **创建日期**：2026-10-24
- **实施目标文件**：`financial-knowledge-rag/static/index.html` (单一文件零依赖极速架构)
- **技术栈**：原生 HTML5 / CSS3 / ES6+ JavaScript（零 npm / 零构建 / 零第三方 CDN 强依赖）
- **关联后端微服务**：`financial-knowledge-rag` (FastAPI 默认端口 `8820`)
- **驱动基础设施**：本地 `codex-bridge` (端口 `9090`) + 本地 PostgreSQL 16 `pgvector`

---

## 1. 业务背景与系统定位

在 `FinancialCopilot` 平台金融知识库 RAG 系统的工程演进中，后端已完成了多模态异构文档解析（PDF/Word/Excel）、表格表头语义挂顶切块、稠密向量+BM25双路召回、本地 GPU 神经重排器（Cross-Encoder）、本地大模型事实溯源审计以及离线/在线效果评测引擎。

为了让金融分析师、风控审查员和算法工程师能够白盒化穿透审查 RAG 系统的切片质量、流水线性能以及召回生成评测，前端需要构建三位一体的**专业级金融终端观测与评测工作台**：

```
┌──────────────────────────────────────────────────────────────────────────────┐
│       FinancialCopilot - 金融投研 RAG 全流程全景可视化观测台 (Port 8820)       │
├──────────────────────┬───────────────────────────────┬───────────────────────┤
│  ⚡ 全流程全景监控   │      🔍 切片全量质检台        │    📊 效果评估中心    │
│  (7-Stage Pipeline)  │   (Chunk Quality Inspector)   │  (Evaluation Center)  │
└──────────────────────┴───────────────────────────────┴───────────────────────┘
```

1. **⚡ 全流程全景监控 (Pipeline Monitor)**：白盒化透视从“文档解析”、“分层切块”、“Embedding 向量化”、“双路粗召回”、“RRF 融合”、“神经重排”到“问答生成与断言审计”的 7 阶段全链路，新增**阶段耗时瀑布流 (Latency Waterfall)** 与 **Stage 7 到 Judge 的一键送检闭环**。
2. **🔍 切片全量质检台 (Chunk Quality Inspector)**：提供全量 103 个切片的高密度质检视图，集成自动化质量评分（表头挂顶、表格撕裂、长度合规、实体完整度）与人工打标核验（通过/瑕疵/重置），采用**纯前端内存缓存 + 乐观更新 (Optimistic UI)** 保证 < 5ms 丝滑交互。
3. **📊 效果评估中心 (Evaluation Center)**：包含 8 组黄金基准评测样例展示、三大检索策略（向量/全文/混合重排）一键横向跑分对比矩阵（Recall/Precision/Hit/MRR/NDCG）、LLM-as-a-Judge 4 维智能裁判台以及人工评估历史看板。

---

## 2. 后端契约核验与接口对齐清单

所有工作台所需后端接口在 `app/api/routes.py` 与 `app/diagnostics/evaluator.py` 中已完整就绪，契约对齐情况如下：

| 工作台模块 | 对应前端功能 | 依赖后端接口路径与方法 | 核心请求/响应参数契约 | 状态 |
| :--- | :--- | :--- | :--- | :--- |
| **通用** | 文档列表下拉 | `GET /api/knowledge/documents` | 返回文档元数据列表（含 `all` 全库聚合项） | 已就绪 |
| **🔍 切片质检台** | 全库/按文档切片拉取 | `GET /api/knowledge/documents/{doc_id}/chunks` | `doc_id="all"` 拉取 103 个切片；包含表格、走势图标记及 `quality_analysis` | 已就绪 |
| **🔍 切片质检台** | 人工核验状态打标 | `POST /api/knowledge/chunks/{chunk_id}/verify` | 入参：`{status: "VERIFIED"\|"DEFECTIVE"\|"UNVERIFIED", notes, verified_by}` | 已就绪 |
| **⚡ 全流程监控** | 7 阶段流水线追踪 | `POST /api/knowledge/pipeline/trace` | 入参：`{query, doc_id}`；返回 `timings` 与 7 个 Stage 详尽指标及事实断言审计 | 已就绪 |
| **📊 效果评估中心** | 黄金评测集概览 | `GET /api/knowledge/evaluation/dataset` | 返回 8 组涵盖跨期、公告、费率、公理的黄金评测题及标准切片列表 | 已就绪 |
| **📊 效果评估中心** | 三大策略横向跑分 | `POST /api/knowledge/evaluation/run_benchmark` | 入参：`{strategy: "all", top_k: 5, score_threshold: 0.35}`；返回指标对比矩阵与逐题明细 | 已就绪 |
| **📊 效果评估中心** | LLM Judge 智能裁判 | `POST /api/knowledge/evaluation/llm_judge` | 入参：`{query, answer, context_chunks, standard_answer}`；返回四维评分（1-5）与判定理由 | 已就绪 |
| **📊 效果评估中心** | 人工评估记录看板 | `GET /api/knowledge/evaluation/human_reviews` / `POST /api/knowledge/evaluation/human_review` | 入参：`{query, answer, score, is_correct, is_faithful, notes, reviewer}` | 已就绪 |

---

## 3. 详细模块设计与交互规范

### 3.1 顶部通用导航栏与多工作台选项卡 (Global Header & Tab Switching)

- **深色金融终端 Header 布局**：
  - 左侧：品牌 Logo `FinancialCopilot` + `RAG 投研全景观测与评估平台`。
  - 中间：工作台三联切换药丸选项卡（Tab Switcher）：
    1. `⚡ 全流程全景监控` (`active-tab = "pipeline"`)
    2. `🔍 切片全量质检台` (`active-tab = "inspector"`)
    3. `📊 效果评估中心` (`active-tab = "evaluation"`)
  - 右侧：微服务拓扑状态指示灯：
    - RAG 微服务：`Port 8820` (绿色霓虹呼吸灯)
    - 模型驱动桥：`Port 9090` (本地 Codex-Bridge Gemini 3.8 Flash)
    - 向量知识库：`PostgreSQL pgvector` (103 个切片已建立索引)
- **页面状态持久化**：
  - 点击选项卡无需刷新页面，主视口以容器隐藏/显示切换，无缝保持每个工作台的状态、搜索框输入及跑分结果。

---

### 3.2 模块一：⚡ 全流程全景监控工作台 (Enhanced Pipeline Monitor)

#### 3.2.1 增强特性一：阶段耗时瀑布流 (Latency Waterfall)
- **数据来源**：`POST /api/knowledge/pipeline/trace` 返回体中的 `timings` 字段：
  ```json
  {
    "stage_1_parse": 4.2,
    "stage_2_split": 2.1,
    "stage_3_embed": 45.6,
    "stage_4_recall": 18.3,
    "stage_5_rrf": 3.4,
    "stage_6_rerank": 35.8,
    "stage_7_generation": 580.0,
    "total": 689.4
  }
  ```
- **UI 呈现方案**：
  - 在 Stepper 导航栏正下方呈现**动态水平堆叠耗时瀑布流**组件。
  - 各阶段采用差异化专属霓虹标识色：
    - Stage 1 解析：青色 (`#38bdf8`)
    - Stage 2 切块：天蓝 (`#0284c7`)
    - Stage 3 向量化：靛青 (`#6366f1`)
    - Stage 4 双路粗召回：紫色 (`#818cf8`)
    - Stage 5 RRF 融合：粉紫 (`#ec4899`)
    - Stage 6 神经重排：琥珀金 (`#f59e0b`)
    - Stage 7 生成与断言审计：祖母绿 (`#10b981`)
  - 瀑布流横条支持交互式 Hover 浮层，直观展示该阶段绝对耗时（ms）与占流水线总耗时的精确百分比。
  - 右侧提供总端到端耗时指示器：`<Total Latency: 689.4 ms>`。

#### 3.2.2 增强特性二：Stage 7 与 LLM-as-a-Judge 的一键送检闭环 (Pipeline to Judge Loop)
- **操作按钮**：在 Stage 7（Generator 问答生成与事实忠诚度双轨溯源审计）卡片右上角增加突出按钮：
  `🎯 一键送入 LLM Judge 智能裁判`。
- **闭环动作流**：
  1. 用户点击按钮，前端自动抓取当前流水线上下文：
     - `query`: 当前输入的检索提问。
     - `answer`: Stage 7 生成的模型法定回答 `answer_text`。
     - `context_chunks`: Stage 6 神经重排输出的 Top-K 切片文本内容（数组）。
     - `standard_answer`: 若命中黄金评测集题目，自动带入标准答案，否则允许留空。
  2. 自动平滑切换全局选项卡至 `📊 效果评估中心` -> `LLM-as-a-Judge 裁判台`。
  3. 将上述内容自动填入表单输入域，带有聚焦微动效，并提供一键触发评测或自动立即评测选项。
  4. 弹出 Toast 提示：“已无缝装载当前 RAG 问答上下文至智能裁判台”。

---

### 3.3 模块二：🔍 切片全量质检台 (Chunk Quality Inspector)

#### 3.3.1 数据架构与纯前端内存缓存 (< 5ms 过滤)
- **全局状态池**：
  - `g_chunksCache`: 存放全量拉取的切片列表（103 条）。
  - `g_filteredChunks`: 当前过滤条件下匹配的切片列表。
  - `g_chunkStats`: 实时汇总统计对象 `{ total: 103, verified: X, defective: Y, unverified: Z, hasTable: A, hasChart: B }`。
- **初次拉取与切换**：
  - 首次进入质检台或切换上方“所属文档”下拉框时，调用 `GET /api/knowledge/documents/{doc_id}/chunks`（默认 `doc_id="all"`）。
  - 数据就绪后存入内存变量，后续所有的多维度联合过滤全部在客户端执行 `Array.prototype.filter`，响应时间控制在 5ms 以内。

#### 3.3.2 质检台控制栏与多维联合过滤
- **搜索输入框**：支持按切片 ID（如 `chunk_doc8821_p3_01`）、所属文档标题、正文关键词模糊搜索。
- **所属文档下拉**：全部文档 (103 切片) / 各独立研报与公告。
- **认知层级过滤 (Cognitive Layer)**：全部 / `GROUND_TRUTH` (基石客观事实) / `HIGH_DENSITY` (高密度测算表) / `COMMON_SENSE` (常识公理)。
- **质检核验状态过滤 (Verification Status)**：全部 / `UNVERIFIED` (待质检) / `VERIFIED` (已核验通过) / `DEFECTIVE` (标记瑕疵)。
- **特征勾选开关**：
  - ☑️ 仅看表格切片 (`has_table: true`)
  - ☑️ 仅看走势图切片 (`has_chart: true`)
  - ☑️ 仅看质检告警 (`quality_analysis.warnings.length > 0`)

#### 3.3.3 质检卡片呈现与自动化质量检测标记 (Quality Analysis)
每个切片卡片展示：
- **卡片 Header**：
  - 状态指示药丸：待核验（灰）/ 核验通过（绿亮）/ 存在瑕疵（红亮）。
  - 切片 ID 与所属页码：如 `P.3 [chunk_doc8821_p3_01]`。
  - 认知层级标签：`GROUND_TRUTH` / `HIGH_DENSITY`。
  - 特征标签：`📊 表格切片`、`📈 走势图解析`。
  - 自动化质量检测徽章：
    - 质量评分：`Quality: 98/100`。
    - 自动化检测项清单：
      - `[✓] 表头挂顶保护`
      - `[✓] 无表格撕裂`
      - `[✓] 长度适中`
      - `[✓] 实体元数据完整`
    - 若存在告警，以黄色/红色警告条列出告警原因（如“表格撕裂缺失尾列”、“长度超标”）。
- **卡片 Body**：
  - 切片正文展示：自动将 Markdown 表格渲染为具有金融交替斑马纹的 HTML Table。
- **卡片 Footer - 人工核验操作栏 (Optimistic UI)**：
  - 按钮组：
    - `✅ 核验通过 (Verify)`
    - `⚠️ 标记瑕疵 (Mark Defective)`
    - `🔄 重置待验 (Reset)`
  - 审核人备注输入框（可选填，如“表头第二列单位缺失”）。

#### 3.3.4 乐观更新 (Optimistic UI) 容错机制
1. 用户点击 `✅ 核验通过` 或 `⚠️ 标记瑕疵` 时：
   - 前端**立即（0ms）**在内存中修改对应切片的 `verification_status` 和 `verification_notes`；
   - 立即刷新当前卡片 DOM 样式，状态指示灯与全局统计计数器秒级变化；
   - 记录修改前快照 `previousStatus`；
2. 异步发起后台网络请求：
   - `POST /api/knowledge/chunks/{chunk_id}/verify`，Payload: `{status: newStatus, notes, verified_by: "human_auditor"}`；
3. 异常回滚保障：
   - 若网络超时或服务端返回 HTTP 500/404，立刻触发回滚逻辑：
     - 将内存对象重置为 `previousStatus`；
     - 恢复卡片 DOM 状态及统计计数器；
     - 右上角弹出红色持久 Toast 警报：“网络异常，切片状态更新失败已自动回滚，请重试！”

---

### 3.4 模块三：📊 效果评估中心 (Evaluation Center)

评估中心分为四个协同子视窗：

#### 3.4.1 子视窗 A：黄金基准评测集概览 (Golden Dataset Viewer)
- **数据源**：`GET /api/knowledge/evaluation/dataset`。
- **呈现**：以列表/网格形式呈现当前固化的 8 个投研典型评测用例：
  - 测试用例分类标签：跨期对比 / 临时公告 / 费率测算 / 金融公理。
  - 核心提问 Query。
  - 标定黄金切片 IDs（GT Chunk IDs，用于 Precision/Recall/MRR 计算）。
  - 标准法定参考答案与推理解释。
  - 操作按钮：
    - `▶️ 送入全流程监控验证`（自动带入 Query 并运行流水线）。
    - `⚖️ 送入智能裁判台`（自动带入标准答案与提问）。

#### 3.4.2 子视窗 B：三大检索策略横向跑分对比矩阵 (Benchmark Matrix)
- **数据源**：`POST /api/knowledge/evaluation/run_benchmark`。
- **入参配置面板**：
  - 检索策略：`all` (三大策略同场竞技) / 稠密向量 / 全文检索 / 混合神经重排。
  - Top-K 深度：`5` (默认) / `10`。
  - 置信度阈值：`0.35`。
  - 启动跑分按钮：`🚀 运行全集自动化基准跑分 (Run Benchmark)`（带加载中 Spinner 与耗时提示）。
- **指标对比矩阵呈现**：
  - 形成专业对比看板，横向比对三种策略在 8 道黄金样例上的评测总览：
    - `Recall@5` (召回率，最关键指标)
    - `Precision@5` (精确度)
    - `Hit@5` (命中率)
    - `MRR` (平均倒数排名)
    - `NDCG@5` (折损累计增益)
    - `平均耗时 (ms)`
  - 优胜高亮标记：混合神经重排策略指标最高项以金色/绿色加粗显示，并标注提升幅度（例如 `+38.5% Recall`）。
- **逐题评测明细展开 (Drill-down)**：
  - 表格列出 8 道题逐一的得分明细，支持点击某道题查看其召回了哪些切片、命中了哪些 GT 切片。

#### 3.4.3 子视窗 C：LLM-as-a-Judge 智能裁判台 (LLM Judge Panel)
- **数据源**：`POST /api/knowledge/evaluation/llm_judge`。
- **输入面板**：
  - 用户查询 (Query)
  - 待评测模型回答 (Candidate Answer)
  - 检索上下文切片内容 (Context Chunks，支持多段切片拼接或由流水线自动传入)
  - 参考标准答案 (Ground Truth Answer，可选)
  - 裁判按钮：`⚖️ 启动大模型裁判打分 (Evaluate with Judge)`。
- **四维打分输出看板**：
  由本地 `codex-bridge` (Gemini Flash) 驱动的裁判模型，输出 1~5 分客观量化分值及详尽判定理由：
  1. **答案正确性 (Correctness, 1-5分)**：对比标准答案与证据切片，事实有无纰漏。
  2. **回答完备性 (Completeness, 1-5分)**：是否涵盖了核心要点，有无遗漏。
  3. **引用一致性 (Citation Consistency, 1-5分)**：每一句结论是否均有切片依据。
  4. **无幻觉程度 (No Hallucination, 1-5分)**：模型是否杜撰未在切片中提及的数字或概念。
- **视觉化呈现**：
  - 4 个计量进度柱（1~5 星级 / 进度条，根据分值渐变：5分绿色、3分橙色、1分红色）。
  - 最终裁定：`PASS (合规放行)` / `REVIEW (需人工复审)` / `FAIL (驳回)`。
  - 裁判评语卡片展示模型思维链与打分理由。

#### 3.4.4 子视窗 D：人工评估历史看板 (Human Review Board)
- **数据源**：`GET /api/knowledge/evaluation/human_reviews` / `POST /api/knowledge/evaluation/human_review`。
- **提交面板**：
  - 分析师可在人工质检时对特定 Query/Answer 进行打标：输入评分（1-5）、是否正确、是否忠实、评审备注与工号，一键保存持久化至 `data/evaluation_records/human_reviews.json`。
- **历史记录看板**：
  - 呈现最近所有人工核验历史，表格支持按工号、状态过滤，保障金融质检溯源合规。

---

## 4. UI 视觉规范与设计系统 (Design System Tokens)

保持统一的深海沉浸式金融投研风格，使用纯原生 CSS Custom Properties 统一管理：

```css
:root {
  /* 背景层次体系 */
  --bg-main: #070c18;          /* 全局主视口深海黑蓝 */
  --bg-panel: #0d1527;         /* 工作台面板石板黑 */
  --bg-card: rgba(15, 23, 42, 0.78); /* 磨砂半透明卡片 */
  --bg-card-hover: rgba(26, 38, 66, 0.92);
  --bg-input: rgba(2, 6, 23, 0.85);

  /* 边框体系 */
  --border: rgba(255, 255, 255, 0.08);
  --border-light: rgba(255, 255, 255, 0.15);
  --border-focus: #38bdf8;

  /* 文字配色 */
  --text-title: #f8fafc;
  --text-body: #cbd5e1;
  --text-muted: #64748b;

  /* 核心功能霓虹色 */
  --accent-cyan: #38bdf8;       /* 向量/召回/主操作 */
  --accent-blue: #0284c7;       /* 品牌强调 */
  --accent-emerald: #10b981;    /* 事实忠实/质检通过/高分 */
  --accent-purple: #818cf8;     /* 神经重排/综合评估 */
  --accent-amber: #f59e0b;      /* 预警/次优/复核 */
  --accent-rose: #f43f5e;       /* 幻觉/瑕疵/未命中 */

  /* 字体系列 */
  --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  --font-code: "JetBrains Mono", Consolas, "SF Mono", monospace;
}
```

- **交互微动效规范**：
  - 按钮 Hover 上浮 1px，投影外发光扩散；
  - 选项卡切换淡入淡出（opacity 0.2s）；
  - Toast 浮动提示自右上角滑入，3.5 秒后平滑移出；
  - 质检状态瞬时切换无白屏卡顿。

---

## 5. 前端 JavaScript 模块架构设计

单一文件 `index.html` 内按职责划分模块对象，杜绝全局变量污染：

```javascript
/**
 * FinancialCopilot RAG Visual Workspace Architecture
 */
const AppState = {
  currentTab: 'pipeline',       // 'pipeline' | 'inspector' | 'evaluation'
  evalSubTab: 'benchmark',      // 'benchmark' | 'golden' | 'judge' | 'reviews'
  
  // 切片质检台状态池 (Client-side In-Memory Cache)
  inspector: {
    allChunks: [],              // 103 个全量切片缓存
    filteredChunks: [],         // 过滤结果
    selectedDocId: 'all',
    filterLayer: 'ALL',
    filterStatus: 'ALL',
    onlyTables: false,
    onlyCharts: false,
    onlyWarnings: false,
    keyword: ''
  },
  
  // 流水线监控状态
  pipeline: {
    lastTraceData: null,
    currentStageView: 'all'
  },
  
  // 效果评估状态
  evaluation: {
    dataset: [],
    benchmarkResult: null,
    judgeResult: null,
    humanReviews: []
  }
};

// 核心服务交互层
const RagApi = {
  getDocuments: () => fetch('/api/knowledge/documents').then(r => r.json()),
  getChunks: (docId) => fetch(`/api/knowledge/documents/${docId}/chunks`).then(r => r.json()),
  verifyChunk: (chunkId, data) => fetch(`/api/knowledge/chunks/${chunkId}/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data)
  }).then(r => r.json()),
  runTrace: (query, docId) => fetch('/api/knowledge/pipeline/trace', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, doc_id: docId })
  }).then(r => r.json()),
  getDataset: () => fetch('/api/knowledge/evaluation/dataset').then(r => r.json()),
  runBenchmark: (req) => fetch('/api/knowledge/evaluation/run_benchmark', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req)
  }).then(r => r.json()),
  runLlmJudge: (req) => fetch('/api/knowledge/evaluation/llm_judge', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req)
  }).then(r => r.json()),
  getHumanReviews: () => fetch('/api/knowledge/evaluation/human_reviews').then(r => r.json()),
  submitHumanReview: (req) => fetch('/api/knowledge/evaluation/human_review', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req)
  }).then(r => r.json())
};
```

---

## 6. 测试与验收标准 (Acceptance Criteria)

1. **界面美学与零依赖**：
   - 页面启动零 404，无任何外部 npm 包依赖或外网 CDN 资源请求；
   - 深海终端配色统一，字体清晰，排版专业。
2. **切片质检台验收**：
   - 加载后拉取全量 103 个切片；
   - 输入关键词、切换认知层级、勾选表格开关，切片列表即时（< 5ms）无缝刷新；
   - 点击“核验通过”或“标记瑕疵”，UI 瞬时变更，后台成功提交 `/verify` 接口并在刷新后状态依然保持；
   - 模拟断网打标时，自动回滚状态并弹出 Toast 警报。
3. **全流程监控验收**：
   - 发起查询后，流水线 7 个阶段数据完整渲染；
   - 耗时瀑布流清晰展现各阶段耗时毫秒数与百分比；
   - Stage 7 点击“🎯 一键送入 LLM Judge 智能裁判”，自动切换至评估中心并将当前问答和切片数据回填到 Judge 面板。
4. **效果评估中心验收**：
   - 黄金评测集 8 个题目正常加载展示；
   - 点击“运行基准跑分”，三大策略对比矩阵完整计算并呈现 Recall/Precision/Hit/MRR/NDCG 指标；
   - 点击“执行裁判”，LLM Judge 正确返回 4 维打分（1-5）与判定原因；
   - 人工评估历史列表能正确读取和提交新记录。
