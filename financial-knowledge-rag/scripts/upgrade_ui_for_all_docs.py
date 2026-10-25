"""升级前端静态工作台，全面展示 5 份法定披露文档及其切分结果 (261 个切块)。

满足用户要求：
1. 在前端看到目前所有文档，以及文档对应的切分结果与详细统计指标；
2. 在前端看到所有切分后的 chunk (卡片流、表格格式化、折线走势图标注、股票代码关联、快速核验)；
3. 保留既有 18 个关键 DOM 节点、onclick 函数与 API 契约，保证测试 100% 通过。
"""

import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

INDEX_FILE = os.path.join(os.path.dirname(__file__), "..", "static", "index.html")

DOCUMENTS_DATA = [
    {
        "id": "doc_4102085",
        "title": "诺安成长混合型证券投资基金2026年第2季度报告",
        "short_title": "2026Q2 季度报告",
        "badge": "2026Q2",
        "badge_color": "var(--accent-cyan)",
        "file_name": "诺安成长混合型证券投资基金2026 年第 2 季度报告2026 年 06 月 30 日.pdf",
        "file_size": "474 KB",
        "publish_date": "2026-07-21",
        "report_period": "2026Q2",
        "total_pages": 13,
        "total_chunks": 47,
        "total_tables": 12,
        "total_charts": 2,
        "total_texts": 33,
        "desc": "法定二季报披露，包含前十大重仓股票（中微公司、北方华创、拓荆科技等）、期末份额变动及累计净值变动走势图。"
    },
    {
        "id": "doc_4102084",
        "title": "诺安成长混合型证券投资基金2026年第1季度报告",
        "short_title": "2026Q1 季度报告",
        "badge": "2026Q1",
        "badge_color": "var(--accent-blue)",
        "file_name": "诺安成长混合型证券投资基金2026 年第 1 季度报告 2026 年 03 月 31.pdf",
        "file_size": "482 KB",
        "publish_date": "2026-04-22",
        "report_period": "2026Q1",
        "total_pages": 13,
        "total_chunks": 46,
        "total_tables": 12,
        "total_charts": 2,
        "total_texts": 32,
        "desc": "法定一季报披露，可与二季报进行跨期仓位对比、份额变动追踪及半导体龙头持仓周期对齐。"
    },
    {
        "id": "doc_ann_val_adj",
        "title": "诺安基金管理有限公司关于旗下基金持有的股票估值调整的公告",
        "short_title": "股票估值调整公告",
        "badge": "估值调整",
        "badge_color": "var(--accent-amber)",
        "file_name": "诺安基金管理有限公司关于旗下基金持有的股票估值调整的公告.pdf",
        "file_size": "70 KB",
        "publish_date": "2026-06-30",
        "report_period": "2026-06",
        "total_pages": 1,
        "total_chunks": 1,
        "total_tables": 0,
        "total_charts": 0,
        "total_texts": 1,
        "desc": "旗下基金对停牌股票拓荆科技(688072)采用指数收益法进行公允估值调整，规范公理级重要凭证。"
    },
    {
        "id": "doc_fund_summary",
        "title": "诺安成长混合型证券投资基金基金产品资料概要更新",
        "short_title": "基金产品资料概要更新",
        "badge": "资料概要",
        "badge_color": "var(--accent-purple)",
        "file_name": "诺安成长混合型证券投资基金基金产品资料概要更新.pdf",
        "file_size": "153 KB",
        "publish_date": "2026-07-27",
        "report_period": "2026-07",
        "total_pages": 5,
        "total_chunks": 7,
        "total_tables": 7,
        "total_charts": 0,
        "total_texts": 0,
        "desc": "涵盖产品费率（管理费率1.20%、托管费率0.20%）、投资组合比例限制（股票资产60%-95%）及核心风险特征。"
    },
    {
        "id": "doc_semi_annual_2026",
        "title": "诺安成长混合型证券投资基金2026年中期报告",
        "short_title": "2026年中期报告",
        "badge": "2026H1 中报",
        "badge_color": "var(--accent-emerald)",
        "file_name": "诺安成长混合型证券投资基金2026年中期报告 2026年 06 月 30 日.pdf",
        "file_size": "1.53 MB",
        "publish_date": "2026-08-28",
        "report_period": "2026H1",
        "total_pages": 53,
        "total_chunks": 160,
        "total_tables": 81,
        "total_charts": 2,
        "total_texts": 77,
        "desc": "53页全量法定中报，完整财务报表、审计意见、全量持仓明细（前百大股票）、重大关联交易及转融通证券借出。"
    }
]


def upgrade():
    with open(INDEX_FILE, "r", encoding="utf-8") as f:
        html = f.read()

    # 1. 更新顶部服务状态条
    html = re.sub(
        r'<div style="background: rgba\(56, 189, 248, 0\.1\); border: 1px solid rgba\(56, 189, 248, 0\.3\); padding: 0\.15rem 0\.6rem; border-radius: 6px; color: var\(--accent-cyan\); font-weight: 600;">\s*📚 [^<]+?\s*</div>',
        '<div style="background: rgba(56, 189, 248, 0.1); border: 1px solid rgba(56, 189, 248, 0.3); padding: 0.15rem 0.6rem; border-radius: 6px; color: var(--accent-cyan); font-weight: 600;">\n        📚 5 份核心投研文档全量已接入 (261 切块 · 112 表格 · 6 走势图)\n      </div>',
        html
    )

    # 2. 更新 Tab 按钮文案
    html = html.replace(
        '<button class="nav-tab-btn" id="tab-btn-inspector" onclick="switchTab(\'inspector\')">🔍 切片全量质检台</button>',
        '<button class="nav-tab-btn" id="tab-btn-inspector" onclick="switchTab(\'inspector\')">📑 文档与切分结果全景台</button>'
    )

    # 3. 更新 doc-select 下拉选项 (5 份文档)
    new_doc_select_options = """      <select id="doc-select" class="doc-dropdown" onchange="onDocSelectChange(this.value)">
        <option value="all" selected>🌐 全量跨期联合检索 (聚合全部 5 份文档 · 261 切块)</option>
        <option value="doc_4102085">📑 诺安成长混合型证券投资基金2026年第2季度报告 (47 切块 · 12 表格 · 2 走势图)</option>
        <option value="doc_4102084">📑 诺安成长混合型证券投资基金2026年第1季度报告 (46 切块 · 12 表格 · 2 走势图)</option>
        <option value="doc_ann_val_adj">⚖️ 诺安基金旗下基金持有股票估值调整公告 (1 切块 · 拓荆科技停牌估值)</option>
        <option value="doc_fund_summary">📘 诺安成长混合型证券投资基金基金产品资料概要更新 (7 切块 · 7 表格 · 费率策略)</option>
        <option value="doc_semi_annual_2026">📑 诺安成长混合型证券投资基金2026年中期报告 (160 切块 · 81 表格 · 2 走势图)</option>
      </select>"""
    html = re.sub(r'<select id="doc-select" class="doc-dropdown" onchange="onDocSelectChange\(this\.value\)">.*?</select>', new_doc_select_options, html, flags=re.DOTALL)

    # 4. 更新 inspector-doc-select 下拉选项
    new_inspector_select_options = """<select id="inspector-doc-select" class="inspector-select" onchange="onInspectorDocChange(this.value)">
          <option value="all">🌐 全量所有文档切片 (5 份文档 · 261 切块)</option>
          <option value="doc_4102085">📑 2026Q2 季报 (47 切块 · 12 表格)</option>
          <option value="doc_4102084">📑 2026Q1 季报 (46 切块 · 12 表格)</option>
          <option value="doc_ann_val_adj">⚖️ 股票估值调整公告 (1 切块)</option>
          <option value="doc_fund_summary">📘 基金产品资料概要更新 (7 切块 · 7 表格)</option>
          <option value="doc_semi_annual_2026">📑 2026年中期报告 (160 切块 · 81 表格)</option>
        </select>"""
    html = re.sub(r'<select id="inspector-doc-select" class="inspector-select" onchange="onInspectorDocChange\(this\.value\)">.*?</select>', new_inspector_select_options, html, flags=re.DOTALL)

    # 5. 在 view-inspector 顶部注入“文档总览卡片区”
    doc_cards_html = """
    <!-- 📚 核心文档全景卡片库 -->
    <div class="docs-hub-banner" style="background: linear-gradient(135deg, rgba(15, 23, 42, 0.95), rgba(30, 41, 59, 0.9)); border-bottom: 1px solid var(--border); padding: 1.2rem 1.5rem;">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.9rem;">
        <div>
          <h2 style="font-size: 1.15rem; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 0.5rem; margin: 0;">
            <span>📚</span>
            <span>当前法定披露文档库与切分全景 (共 5 份核心文档 · 261 语义切块)</span>
          </h2>
          <div style="font-size: 0.8rem; color: var(--text-muted); margin-top: 0.25rem;">
            顺应大纲标题、跨页表格缝合与图表提取引擎驱动，点击任意文档卡片可即时筛选其对应的切分切块
          </div>
        </div>
        <div style="display: flex; gap: 0.6rem;">
          <button class="btn-send-judge" onclick="loadAllDocumentsHub()" style="padding: 0.35rem 0.8rem; font-size: 0.8rem;">🔄 刷新文档状态</button>
        </div>
      </div>

      <!-- 5 份文档卡片网格 -->
      <div class="doc-cards-grid" id="doc-cards-grid" style="display: grid; grid-template-columns: repeat(auto-fit, minmax(290px, 1fr)); gap: 0.85rem;">
"""
    for doc in DOCUMENTS_DATA:
        doc_cards_html += f"""
        <div class="doc-overview-card" id="doc-card-{doc['id']}" onclick="filterByDoc('{doc['id']}')" style="background: rgba(2, 6, 23, 0.65); border: 1px solid var(--border); border-radius: 10px; padding: 0.85rem 1rem; cursor: pointer; transition: all 0.2s ease;">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 0.45rem;">
            <span style="font-size: 0.72rem; font-weight: 700; padding: 0.15rem 0.5rem; border-radius: 4px; background: rgba(56, 189, 248, 0.12); color: {doc['badge_color']}; border: 1px solid rgba(56, 189, 248, 0.25);">{doc['badge']}</span>
            <span style="font-size: 0.72rem; color: var(--text-muted);">{doc['publish_date']}</span>
          </div>
          <div style="font-size: 0.88rem; font-weight: 600; color: var(--text-title); line-height: 1.35; margin-bottom: 0.4rem; height: 2.7rem; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;" title="{doc['title']}">
            {doc['title']}
          </div>
          <div style="display: flex; gap: 0.4rem; flex-wrap: wrap; margin-bottom: 0.6rem; font-size: 0.75rem;">
            <span style="background: rgba(255,255,255,0.04); padding: 0.1rem 0.45rem; border-radius: 4px; color: var(--text-muted);">📄 {doc['total_pages']} 页 ({doc['file_size']})</span>
            <span style="background: rgba(56, 189, 248, 0.1); padding: 0.1rem 0.45rem; border-radius: 4px; color: var(--accent-cyan); font-weight: 600;">🗂️ {doc['total_chunks']} 切块</span>
            <span style="background: rgba(16, 185, 129, 0.1); padding: 0.1rem 0.45rem; border-radius: 4px; color: var(--accent-emerald);">📊 {doc['total_tables']} 表</span>
            {f'<span style="background: rgba(168, 85, 247, 0.1); padding: 0.1rem 0.45rem; border-radius: 4px; color: var(--accent-purple);">📈 {doc["total_charts"]} 图</span>' if doc['total_charts'] > 0 else ''}
          </div>
          <div style="display: flex; justify-content: space-between; align-items: center; border-top: 1px solid rgba(255, 255, 255, 0.05); padding-top: 0.45rem;">
            <span style="font-size: 0.73rem; color: var(--accent-cyan); font-weight: 600;">🔍 查看该文档切块 &rarr;</span>
            <button onclick="event.stopPropagation(); openPipelineWithDoc('{doc['id']}')" style="background: transparent; border: 1px solid rgba(255, 255, 255, 0.15); color: var(--text-body); font-size: 0.72rem; padding: 0.15rem 0.5rem; border-radius: 4px; cursor: pointer;">⚡ 流水线检索</button>
          </div>
        </div>
"""
    doc_cards_html += """      </div>
    </div>
"""

    # 替换 view-inspector 内部头部
    if '<div class="docs-hub-banner"' not in html:
        html = html.replace(
            '<div id="view-inspector" class="workbench-view hidden" style="display:none;">',
            f'<div id="view-inspector" class="workbench-view hidden" style="display:none;">\n{doc_cards_html}'
        )

    # 6. 更新 onDocSelectChange 的 badge 文本 (全量 5 份文档)
    new_on_doc_change_body = """    function onDocSelectChange(docId) {
      const badge = document.getElementById('doc-active-badge');
      if (!badge) return;
      if (docId === 'all') {
        badge.innerHTML = '<span>✓ 全部 5 份核心文档 261 切块联合就绪</span>';
      } else if (docId === 'doc_4102085') {
        badge.innerHTML = '<span>✓ 2026Q2季报 (47切块·12表·2图)</span>';
      } else if (docId === 'doc_4102084') {
        badge.innerHTML = '<span>✓ 2026Q1季报 (46切块·12表·2图)</span>';
      } else if (docId === 'doc_ann_val_adj') {
        badge.innerHTML = '<span>✓ 估值调整公告 (拓荆科技 688072)</span>';
      } else if (docId === 'doc_fund_summary') {
        badge.innerHTML = '<span>✓ 产品资料概要 (7切块·费率·投资限制)</span>';
      } else if (docId === 'doc_semi_annual_2026') {
        badge.innerHTML = '<span>✓ 2026年中期报告 (160切块·81表·2图)</span>';
      }
    }"""
    html = re.sub(r'function onDocSelectChange\(docId\)\s*\{.*?\}', new_on_doc_change_body, html, flags=re.DOTALL)

    # 7. 添加 filterByDoc, openPipelineWithDoc, loadAllDocumentsHub 交互函数
    doc_interact_fns = """
    function filterByDoc(docId) {
      const sel = document.getElementById('inspector-doc-select');
      if (sel) {
        sel.value = docId;
      }
      document.querySelectorAll('.doc-overview-card').forEach(c => {
        c.style.borderColor = 'var(--border)';
        c.style.background = 'rgba(2, 6, 23, 0.65)';
      });
      const targetCard = document.getElementById(`doc-card-${docId}`);
      if (targetCard) {
        targetCard.style.borderColor = 'var(--accent-cyan)';
        targetCard.style.background = 'rgba(56, 189, 248, 0.08)';
      }
      loadInspectorChunks(docId);
      showToast(`已筛选文档: ${docId}`, 'info');
    }

    function openPipelineWithDoc(docId) {
      switchTab('pipeline');
      const docSel = document.getElementById('doc-select');
      if (docSel) {
        docSel.value = docId;
        onDocSelectChange(docId);
      }
      showToast(`已将检索范围锁定为: ${docId}`, 'info');
    }

    async function loadAllDocumentsHub() {
      showToast('已刷新 5 份核心文档状态', 'success');
      loadInspectorChunks('all');
    }
"""

    if "function filterByDoc(" not in html:
        html = html.replace("async function loadInspectorChunks(docId = 'all') {", f"{doc_interact_fns}\n    async function loadInspectorChunks(docId = 'all') {{")

    # 8. 丰富切片卡片渲染，展示所属文档、股票代码、格式化折叠
    old_card_body = """            <div class="inspector-card-body">
              ${formatContent(c.content)}
            </div>"""

    new_card_body = """            <!-- 股票/基金代码徽章与所属文档 -->
            <div style="font-size: 0.74rem; display: flex; gap: 0.4rem; flex-wrap: wrap;">
              <span style="color: var(--accent-cyan); font-weight: 600;">📑 ${c.doc_title || '诺安成长披露文档'}</span>
              ${(c.stock_codes && c.stock_codes.length) ? c.stock_codes.map(sc => `<span style="background: rgba(245, 158, 11, 0.15); border: 1px solid rgba(245, 158, 11, 0.3); color: var(--accent-amber); padding: 0.05rem 0.35rem; border-radius: 3px;">🏷️ ${sc}</span>`).join('') : ''}
              ${(c.fund_codes && c.fund_codes.length) ? c.fund_codes.map(fc => `<span style="background: rgba(56, 189, 248, 0.15); border: 1px solid rgba(56, 189, 248, 0.3); color: var(--accent-cyan); padding: 0.05rem 0.35rem; border-radius: 3px;">💼 ${fc}</span>`).join('') : ''}
            </div>

            <div class="inspector-card-body" style="max-height: 280px; overflow-y: auto; font-family: var(--font-code); font-size: 0.78rem; line-height: 1.5; background: rgba(2, 6, 23, 0.6); padding: 0.6rem; border-radius: 6px; border: 1px solid rgba(255, 255, 255, 0.05);">
              ${formatContent(c.content)}
            </div>"""

    if "c.doc_title || '诺安成长披露文档'" not in html:
        html = html.replace(old_card_body, new_card_body)

    with open(INDEX_FILE, "w", encoding="utf-8") as f:
        f.write(html)

    print("✓ 成功升级 static/index.html！")


if __name__ == "__main__":
    upgrade()
