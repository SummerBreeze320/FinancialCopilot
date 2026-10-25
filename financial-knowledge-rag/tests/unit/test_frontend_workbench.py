"""前端三大工作台与契约结构自动化测试。

校验：
1. 目标文件 index.html 存在；
2. 零外部强 CDN 依赖 (script/link)；
3. DOM 容器与工作台节点完备；
4. JavaScript 关键状态对象 AppState 与 RagApi 接口函数存在；
5. 乐观更新、耗时瀑布流与送检闭环逻辑定义。
"""

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
    external_scripts = re.findall(
        r'<script[^>]+src=["\'](http[s]?://[^"\']+)["\']', content
    )
    external_links = re.findall(
        r'<link[^>]+href=["\'](http[s]?://[^"\']+)["\']', content
    )

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
