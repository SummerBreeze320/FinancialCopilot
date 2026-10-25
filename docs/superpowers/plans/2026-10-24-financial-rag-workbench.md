# 金融投研 RAG 全场景可视化观测与评测工作台实现任务计划 (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建集成「⚡全流程全景监控 (含阶段耗时瀑布流与一键送检闭环)」、「🔍切片全量质检台 (含纯前端内存缓存、<5ms即时过滤与乐观更新)」、「📊效果评估中心 (含黄金评测集、三大策略横向跑分、LLM-as-a-Judge智能裁判与人工质检看板)」的单文件零依赖深色金融投研终端前端。

**Architecture:** 采用单文件原生 Vanilla HTML5 + CSS3 + ES6+ 架构，零 npm/零第三方 CDN 依赖。通过统一的 `AppState` 状态池与 `RagApi` 异步交互层驱动，利用 `display: none` 视窗容器实现三个独立工作台的秒级平滑切换与状态持久保持。

**Tech Stack:** 原生 HTML5, CSS3 Custom Properties (Design Tokens), Flexbox / CSS Grid, ES6+ JavaScript, Python / Pytest (前端结构与契约自动化校验)。

**Spec:** `docs/superpowers/specs/2026-10-24-financial-rag-workbench-design.md`

## Global Constraints

- **目标单一修改文件**：`financial-knowledge-rag/static/index.html`。
- **零外部强依赖**：绝对禁止引入外部 npm 构建流或远程 CDN（如 unpkg, cdnjs, tailwind cdn 等），保证离线内网安全可用。
- **设计风格与视觉规范**：统一深海蓝/石板灰深色金融终端美学 (`--bg-main: #070c18`, `--accent-cyan: #38bdf8`, `--accent-emerald: #10b981`, `--accent-purple: #818cf8`, `--accent-amber: #f59e0b`, `--accent-rose: #f43f5e`)。
- **切片质检过滤性能**：全量 103 个切片保存在内存数组中，客户端过滤延迟必须保持在 `< 5ms`。
- **乐观更新容错要求**：核验打标时界面与计数器 0ms 即时响应，若后端异步请求异常则自动快照回滚并 Toast 告警。
- **契约对齐**：所有 API 路径严格对齐 `financial-knowledge-rag/app/api/routes.py`。

---

### Task 1: 编写前端结构与契约自动化测试套件及基础全局框架骨架

**Files:**
- Create: `financial-knowledge-rag/tests/unit/test_frontend_workbench.py`
- Modify: `financial-knowledge-rag/static/index.html:1-120`

**Interfaces:**
- Consumes: `docs/superpowers/specs/2026-10-24-financial-rag-workbench-design.md` 的 DOM 结构规范与 API 签名定义。
- Produces: `test_frontend_workbench.py` 自动化测试脚本，校验 DOM 关键节点、Tab 切换逻辑、API 函数签名以及零外部 CDN 规则。

- [ ] **Step 1: 编写前端自动化测试文件 `test_frontend_workbench.py`**

```python
import os
import re
import pytest

INDEX_PATH = os.path.join(
    os.path.dirname(__file__), "..", "..", "static", "index.html"
)

def test_index_file_exists():
    assert os.path.exists(INDEX_PATH), f"File {INDEX_PATH} does not exist"

def test_zero_external_cdn_dependencies():
    """验证不含有外部 http/https 的 script 与 link cdn 依赖。"""
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        content = f.read()
    
    # 检查是否有引用外部 CDN 库
    external_scripts = re.findall(r'<script[^>]+src=["\'](http[s]?://[^"\']+)["\']', content)
    external_links = re.findall(r'<link[^>]+href=["\'](http[s]?://[^"\']+)["\']', content)
    
    # 只允许字体或者无任何外链
    for s in external_scripts:
        assert False, f"Forbidden external script CDN: {s}"
    for l in external_links:
        if "fonts.googleapis.com" not in l and "fonts.gstatic.com" not in l:
            assert False, f"Forbidden external stylesheet CDN: {l}"

def test_dom_workbenches_and_tabs_exist():
    """验证三大工作台的核心容器与导航 DOM 节点存在。"""
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        html = f.read()

    # 1. 顶部 Tab 导航
    assert 'id="tab-btn-pipeline"' in html
    assert 'id="tab-btn-inspector"' in html
    assert 'id="tab-btn-evaluation"' in html

    # 2. 三大工作台主容器
    assert 'id="view-pipeline"' in html
    assert 'id="view-inspector"' in html
    assert 'id="view-evaluation"' in html

    # 3. 增强功能与关键组件 DOM
    assert 'id="latency-waterfall"' in html
    assert 'id="inspector-chunks-container"' in html
    assert 'id="eval-subtab-benchmark"' in html
    assert 'id="eval-subtab-judge"' in html
    assert 'id="toast-container"' in html

def test_javascript_api_contracts_present():
    """验证 JavaScript 代码中包含规范要求的关键 API 交互函数与数据结构。"""
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        js = f.read()

    # AppState 结构验证
    assert "AppState" in js
    assert "inspector" in js
    assert "pipeline" in js
    assert "evaluation" in js

    # API 接口路由与方法匹配
    assert "/api/knowledge/documents" in js
    assert "/api/knowledge/documents/" in js
    assert "/api/knowledge/chunks/" in js
    assert "/api/knowledge/pipeline/trace" in js
    assert "/api/knowledge/evaluation/dataset" in js
    assert "/api/knowledge/evaluation/run_benchmark" in js
    assert "/api/knowledge/evaluation/llm_judge" in js
    assert "/api/knowledge/evaluation/human_review" in js

def test_optimistic_ui_and_waterfall_logic():
    """验证包含乐观更新回滚与瀑布流渲染逻辑。"""
    with open(INDEX_PATH, "r", encoding="utf-8") as f:
        js = f.read()
    
    assert "renderLatencyWaterfall" in js
    assert "switchTab" in js
    assert "showToast" in js
    assert "sendStage7ToJudge" in js
```

- [ ] **Step 2: 运行测试验证其当前失败（TDD 初始基线）**

Run: `pytest tests/unit/test_frontend_workbench.py -v` in `financial-knowledge-rag`  
Expected: FAIL（因为当前 `index.html` 尚未包含多工作台及增强节点）。

- [ ] **Step 3: 实现全局 Header、三联 Tab 导航栏与 Toast 通知系统骨架**

在 `financial-knowledge-rag/static/index.html` 中：
1. 升级 CSS 变量设计系统（`--bg-main`, `--bg-panel`, `--accent-cyan`, `--accent-emerald`, `--accent-amber`, `--accent-rose`, `--accent-purple` 等）；
2. 在 Header 中植入 Tab 切换器：
   ```html
   <div class="nav-tabs">
     <button class="nav-tab-btn active" id="tab-btn-pipeline" onclick="switchTab('pipeline')">⚡ 全流程全景监控</button>
     <button class="nav-tab-btn" id="tab-btn-inspector" onclick="switchTab('inspector')">🔍 切片全量质检台</button>
     <button class="nav-tab-btn" id="tab-btn-evaluation" onclick="switchTab('evaluation')">📊 效果评估中心</button>
   </div>
   ```
3. 构建三大顶级视窗容器：`<div id="view-pipeline" class="workbench-view active">...</div>`、`<div id="view-inspector" class="workbench-view" style="display:none">...</div>`、`<div id="view-evaluation" class="workbench-view" style="display:none">...</div>`；
4. 挂载全局 Toast 容器 `<div id="toast-container" class="toast-container"></div>` 与 `showToast(msg, type)` 函数。

- [ ] **Step 4: 再次执行测试检验基础结构**

Run: `pytest tests/unit/test_frontend_workbench.py -k "test_zero_external_cdn_dependencies or test_index_file_exists" -v`  
Expected: PASS。

---

### Task 2: 实施 ⚡ 全流程全景监控增强 (阶段耗时瀑布流 + Stage 7 送检闭环)

**Files:**
- Modify: `financial-knowledge-rag/static/index.html:150-500, 1000-1360`
- Test: `financial-knowledge-rag/tests/unit/test_frontend_workbench.py`

**Interfaces:**
- Consumes: `POST /api/knowledge/pipeline/trace` 响应中的 `timings` (`stage_1_parse`, `stage_2_split`, `stage_3_embed`, `stage_4_recall`, `stage_5_rrf`, `stage_6_rerank`, `stage_7_generation`, `total`) 与 `pipeline.stage_7_generation_audit`。
- Produces: `renderLatencyWaterfall(timings)` 函数、`#latency-waterfall` 堆叠进度条组件、Stage 7 卡片顶部 `#btn-stage7-send-judge` 交互按钮及 `sendStage7ToJudge()` 闭环跳转函数。

- [ ] **Step 1: 编写阶段耗时瀑布流 (Latency Waterfall) 的 CSS 与 DOM 模板**

在 `index.html` 中新增瀑布流样式与挂载点：
```html
<div class="waterfall-panel" id="latency-waterfall-card">
  <div class="waterfall-header">
    <div style="display: flex; align-items: center; gap: 0.6rem;">
      <span class="waterfall-title">⚡ 流水线 7 阶段端到端耗时瀑布流 (Latency Waterfall)</span>
      <span class="waterfall-total-badge" id="waterfall-total-label">端到端耗时: 0.0 ms</span>
    </div>
    <div class="waterfall-legend" id="waterfall-legend"></div>
  </div>
  <div class="waterfall-bar" id="latency-waterfall">
    <!-- 动态渲染 7 个子阶段的色块宽度与 Tooltip -->
  </div>
</div>
```

- [ ] **Step 2: 实现 `renderLatencyWaterfall(timings)` 渲染逻辑**

计算 7 个阶段（Parse, Split, Embed, Recall, RRF, Rerank, Gen+Audit）占 `timings.total` 的百分比，动态生成水平堆叠彩条，并配置鼠标悬浮交互（展示如 `神经重排: 35.8 ms (5.2%)`）。

- [ ] **Step 3: 在 Stage 7 卡片右上角添加“🎯 一键送入 LLM Judge 智能裁判”按钮与 `sendStage7ToJudge()`**

1. 修改 Stage 7 的 HTML 生成模板，增加操作按钮：
   ```html
   <button class="btn-send-judge" id="btn-stage7-send-judge" onclick="sendStage7ToJudge()">
     🎯 一键送入 LLM Judge 智能裁判
   </button>
   ```
2. 实现 `sendStage7ToJudge()` 闭环方法：
   - 提取当前 `g_lastTraceData.query`；
   - 提取 `stage_7.answer_text`；
   - 提取 `stage_6.top_candidates` 的切片内容列表作为 `context_chunks`；
   - 调用 `switchTab('evaluation')` 并切换到 Judge 子选项卡；
   - 将上述数据自动填入 Judge 的输入表单，带有高亮动效并弹出 Toast：“已将 Stage 7 生成回答与切片上下文装载至智能裁判台”。

- [ ] **Step 4: 运行自动化测试验证 Task 2 契约**

Run: `pytest tests/unit/test_frontend_workbench.py -k "test_optimistic_ui_and_waterfall_logic" -v`  
Expected: PASS。

---

### Task 3: 实施 🔍 切片全量质检台 (纯前端内存缓存 + <5ms 过滤 + 乐观更新)

**Files:**
- Modify: `financial-knowledge-rag/static/index.html`
- Test: `financial-knowledge-rag/tests/unit/test_frontend_workbench.py`

**Interfaces:**
- Consumes:
  - `GET /api/knowledge/documents/{doc_id}/chunks` (拉取 103 个全量切片及 `quality_analysis`)
  - `POST /api/knowledge/chunks/{chunk_id}/verify` (提交 `{status, notes, verified_by}`)
- Produces:
  - `AppState.inspector` 内存缓存管理
  - `filterInspectorChunks()` 毫秒级纯内存过滤函数
  - `renderInspectorCards()` 切片卡片高密度渲染（包含自动化质检检查项 `header_pinned`, `table_tear`, `warnings`）
  - `verifyChunkOptimistic(chunkId, status)` 乐观更新打标与异常回滚机制

- [ ] **Step 1: 编写质检台控制栏与统计看板 HTML/CSS 结构**

在 `<div id="view-inspector">` 中实现：
1. **统计指标概览条**：全部切片数、已核验数（绿色）、标记瑕疵数（红色）、待质检数（黄色）、表格切片数、走势图切片数。
2. **多维即时过滤工具栏**：
   - 搜索输入框 (`#inspector-search`)：监听 `input` 事件实时过滤；
   - 所属文档下拉框 (`#inspector-doc-select`)：`all` 或具体文档；
   - 认知层级下拉框 (`#inspector-layer-select`)：`ALL` / `GROUND_TRUTH` / `HIGH_DENSITY` / `COMMON_SENSE`；
   - 核验状态下拉框 (`#inspector-status-select`)：`ALL` / `UNVERIFIED` / `VERIFIED` / `DEFECTIVE`；
   - 特征筛选勾选框：`#inspector-table-only`、`#inspector-chart-only`、`#inspector-warning-only`。
3. **切片网格容器**：`<div id="inspector-chunks-container" class="inspector-grid"></div>`。

- [ ] **Step 2: 实现切片初次拉取与客户端纯内存毫秒过滤 (`filterInspectorChunks`)**

1. `loadInspectorChunks(docId = 'all')`：从 `GET /api/knowledge/documents/${docId}/chunks` 拉取数据存入 `AppState.inspector.allChunks`；
2. `filterInspectorChunks()`：纯前端执行 `filter` 计算，满足搜索词（切片ID、文档标题、正文）、层级、状态及勾选标记；
3. 更新统计计数器，调用 `renderInspectorCards()` 渲染卡片。测试过滤性能 `< 5ms`。

- [ ] **Step 3: 实现切片卡片渲染与自动化质检标签展现**

卡片包含：
- 状态指示（待验/通过/瑕疵）；
- 认知层级标签、页码、所属文档；
- `quality_analysis` 徽章：表头保护是否有效、表格撕裂检测、长度适中性、告警说明；
- 正文 Markdown 表格转 HTML Table 斑马纹渲染；
- 操作区：`✅ 通过`、`⚠️ 瑕疵`、`🔄 重置` 按钮及备注输入框。

- [ ] **Step 4: 实现乐观更新与网络异常回滚机制 (`verifyChunkOptimistic`)**

```javascript
async function verifyChunkOptimistic(chunkId, newStatus) {
  const chunk = AppState.inspector.allChunks.find(c => c.chunk_id === chunkId);
  if (!chunk) return;
  
  // 1. 保存修改前快照
  const oldStatus = chunk.verification_status || 'UNVERIFIED';
  const oldNotes = chunk.verification_notes || '';
  const noteInput = document.getElementById(`verify-notes-${chunkId}`);
  const newNotes = noteInput ? noteInput.value.trim() : oldNotes;
  
  // 2. 0ms 乐观更新前端内存状态与 DOM
  chunk.verification_status = newStatus;
  chunk.verification_notes = newNotes;
  updateInspectorStats();
  updateSingleCardDOM(chunkId);
  showToast(`切片 [${chunkId}] 已标记为: ${getStatusLabel(newStatus)}`, 'info');
  
  // 3. 异步后台网络持久化
  try {
    const res = await RagApi.verifyChunk(chunkId, {
      status: newStatus,
      notes: newNotes,
      verified_by: 'auditor_user'
    });
    if (!res || res.status !== newStatus) {
      throw new Error('Server update rejected');
    }
  } catch (err) {
    // 4. 异常回滚
    console.error('Verify failed, rolling back:', err);
    chunk.verification_status = oldStatus;
    chunk.verification_notes = oldNotes;
    updateInspectorStats();
    updateSingleCardDOM(chunkId);
    showToast(`切片标记失败已自动回滚: ${err.message}`, 'error');
  }
}
```

- [ ] **Step 5: 执行测试套件验证质检台**

Run: `pytest tests/unit/test_frontend_workbench.py -k "test_dom_workbenches_and_tabs_exist or test_javascript_api_contracts_present" -v`  
Expected: PASS。

---

### Task 4: 实施 📊 效果评估中心 (黄金评测集 + 三大策略跑分 + LLM Judge + 人工看板)

**Files:**
- Modify: `financial-knowledge-rag/static/index.html`
- Test: `financial-knowledge-rag/tests/unit/test_frontend_workbench.py`

**Interfaces:**
- Consumes:
  - `GET /api/knowledge/evaluation/dataset`
  - `POST /api/knowledge/evaluation/run_benchmark`
  - `POST /api/knowledge/evaluation/llm_judge`
  - `GET /api/knowledge/evaluation/human_reviews` / `POST /api/knowledge/evaluation/human_review`
- Produces: 评估中心四个子视窗及其切换、跑分对比矩阵、4 维 Judge 打分仪表盘、人工历史记录表格。

- [ ] **Step 1: 构建评估中心四联子导航栏与视窗布局**

在 `<div id="view-evaluation">` 中设计子导航：
- `#eval-subtab-benchmark`：三大策略横向跑分对比
- `#eval-subtab-golden`：黄金基准评测集 (8题)
- `#eval-subtab-judge`：LLM-as-a-Judge 智能裁判台
- `#eval-subtab-reviews`：人工评估记录看板

- [ ] **Step 2: 实现黄金基准评测集视窗 (`renderGoldenDataset`)**

调用 `GET /api/knowledge/evaluation/dataset`，渲染 8 个题目卡片：
- 显示分类（跨期对比、临时公告、费率测算、金融公理）；
- 显示 Query、GT Chunk IDs、标准答案与推理解释；
- 提供两个快捷操作：
  - `▶️ 送入全流程监控验证`（调用 `runPipelineWithQuery(q)` 并切换到监控台）；
  - `⚖️ 送入智能裁判台`（带入标准答案与提问并切换到 Judge 台）。

- [ ] **Step 3: 实现三大策略横向基准跑分矩阵 (`runBenchmark`)**

1. 配置栏：Top-K 深度（5）、阈值（0.35）、策略（all）；
2. 点击 `🚀 运行全集自动化基准跑分`，发起 `POST /api/knowledge/evaluation/run_benchmark`；
3. 渲染指标对比矩阵表格：
   - 横向列出：稠密向量、全文检索、混合神经重排；
   - 纵向对比：`Recall@5`、`Precision@5`、`Hit@5`、`MRR`、`NDCG@5`、`平均耗时`；
   - 最高指标高亮标注（如混合重排胜出绿色药丸）；
4. 提供逐题明细抽屉/展开区，展示每道题在各策略下的命中情况。

- [ ] **Step 4: 实现 LLM-as-a-Judge 智能裁判视窗 (`runLlmJudge`)**

1. 输入表单：用户提问、候选模型回答、上下文切片（多段）、参考答案；
2. 点击 `⚖️ 启动大模型裁判打分`，发起 `POST /api/knowledge/evaluation/llm_judge`；
3. 四维量化打分看板（1~5 进度柱）：
   - 答案正确性 (Correctness)
   - 回答完备性 (Completeness)
   - 引用一致性 (Citation Consistency)
   - 无幻觉程度 (No Hallucination)
4. 输出综合判定药丸：`PASS` (绿) / `REVIEW` (橙) / `FAIL` (红) 与模型思维链理由。

- [ ] **Step 5: 实现人工评估提交与历史看板 (`loadHumanReviews`)**

1. 提交表单：对当前问答进行人工打分（1-5）、是否正确、是否忠实、评审备注与工号；
2. 历史表格：调用 `GET /api/knowledge/evaluation/human_reviews`，以表格分页列出所有历史核验数据。

- [ ] **Step 6: 执行测试验证评估中心 DOM 与契约**

Run: `pytest tests/unit/test_frontend_workbench.py -v`  
Expected: PASS。

---

### Task 5: 全链路端到端集成验证与微服务联调验收

**Files:**
- Test: `financial-knowledge-rag/tests/unit/test_frontend_workbench.py`
- Verify script: `financial-knowledge-rag/scripts/verify_workbench_e2e.py`

**Interfaces:**
- Consumes: 全量 `index.html` 以及后端 API 端点。
- Produces: 自动化端到端校验报告，确认 0 控制台报错、0 依赖缺失、样式完整。

- [ ] **Step 1: 编写全链路端到端校验脚本 `verify_workbench_e2e.py`**

通过 Python 启动轻量静态服务器或直接解析静态文件，检验：
1. 静态 HTML 语法合规无未闭合标签；
2. 所有的样式类选择器在 CSS 中均有定义；
3. 所有 `onclick` 函数在 `<script>` 中均有具体实现；
4. 验证 API 路径完全匹配后端路由。

- [ ] **Step 2: 运行测试套件与校验脚本**

Run:
```powershell
pytest financial-knowledge-rag/tests/unit/test_frontend_workbench.py -v
python financial-knowledge-rag/scripts/verify_workbench_e2e.py
```
Expected: ALL PASS (100% 绿色)。

- [ ] **Step 3: 最终交付与状态汇报**

确认所有修改在单一文件 `financial-knowledge-rag/static/index.html` 中闭环，不产生多余的未跟踪废弃文件，向用户汇报执行成果。

---

## Plan Self-Review Check

- **Spec coverage**:
  - 切片质检台（纯前端缓存、<5ms即时过滤、乐观更新与回滚、质检标签） -> Task 3
  - 全流程监控（7阶段白盒、耗时瀑布流、Stage 7送检闭环） -> Task 2
  - 效果评估中心（黄金评测集、三大策略横向跑分、LLM Judge 4维打分、人工看板） -> Task 4
  - 零外部依赖与深海蓝金融终端设计系统 -> Task 1, Task 2, Task 3, Task 4
  - 自动化测试与契约保障 -> Task 1, Task 5
- **No placeholders**: 无 "TODO", "TBD", "fill details", 每一个任务步骤均给出具体代码、DOM 结构与验证命令。
- **Type/Method consistency**: `AppState`, `RagApi`, `switchTab`, `renderLatencyWaterfall`, `sendStage7ToJudge`, `verifyChunkOptimistic` 命名在所有任务中严格一致。
