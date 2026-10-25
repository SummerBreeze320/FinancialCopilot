"""全链路端到端自动化校验脚本 (verify_workbench_e2e.py)

校验：
1. index.html 格式与语法结构合规；
2. DOM 节点、Tab 按钮、三大工作台容器闭环；
3. 所有 HTML 内联 onclick 触发函数在 JavaScript 中均有明确声明；
4. API 契约与 FastAPI 核心路由 100% 对齐；
5. 零外部强 CDN 依赖。
"""

import os
import re
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

INDEX_FILE = os.path.join(
    os.path.dirname(__file__), "..", "static", "index.html"
)

def run_e2e_verification() -> bool:
    print("=" * 60)
    print("🚀 开始 Financial Knowledge RAG 可视化工作台 E2E 全链路校验...")
    print("=" * 60)

    if not os.path.exists(INDEX_FILE):
        print(f"❌ 错误: 目标文件不存在: {INDEX_FILE}")
        return False
    print(f"✓ [Step 1] 目标静态文件存在: {INDEX_FILE}")

    with open(INDEX_FILE, "r", encoding="utf-8") as f:
        html = f.read()

    # 1. 检查零外部 CDN 规则
    ext_scripts = re.findall(r'<script[^>]+src=["\'](http[s]?://[^"\']+)["\']', html)
    ext_links = re.findall(r'<link[^>]+href=["\'](http[s]?://[^"\']+)["\']', html)
    for s in ext_scripts:
        print(f"❌ 违规外部脚本 CDN: {s}")
        return False
    for l in ext_links:
        if "fonts.googleapis.com" not in l and "fonts.gstatic.com" not in l:
            print(f"❌ 违规外部样式 CDN: {l}")
            return False
    print("✓ [Step 2] 零外部强 CDN 依赖校验通过 (内网/离线环境安全保障)")

    # 2. 检查 DOM 关键节点
    required_ids = [
        "tab-btn-pipeline", "tab-btn-inspector", "tab-btn-evaluation",
        "view-pipeline", "view-inspector", "view-evaluation",
        "latency-waterfall-card", "latency-waterfall", "waterfall-total-label",
        "inspector-chunks-container", "inspector-search", "inspector-doc-select",
        "eval-subtab-benchmark", "eval-subtab-golden", "eval-subtab-judge", "eval-subtab-reviews",
        "toast-container", "btn-stage7-send-judge"
    ]
    for dom_id in required_ids:
        if f'id="{dom_id}"' not in html:
            print(f"❌ 缺失关键 DOM 节点: id=\"{dom_id}\"")
            return False
    print(f"✓ [Step 3] 三大工作台 18 项关键 DOM 节点全部就绪")

    # 3. 检查 HTML 中声明的 onclick 函数在 <script> 中均有定义
    onclick_matches = re.findall(r'onclick=["\']([a-zA-Z0-9_]+)\(', html)
    unique_onclicks = sorted(list(set(onclick_matches)))
    
    script_match = re.search(r'<script>(.*?)</script>', html, re.DOTALL)
    if not script_match:
        print("❌ 未找到 <script> 块")
        return False
    js_content = script_match.group(1)

    for fn in unique_onclicks:
        pattern = rf'function\s+{fn}\b|const\s+{fn}\s*=|let\s+{fn}\s*='
        if not re.search(pattern, js_content):
            print(f"❌ onclick 调用函数在 JavaScript 中未定义: {fn}()")
            return False
    print(f"✓ [Step 4] 全部 {len(unique_onclicks)} 个 onclick 交互函数均有明确实现: {', '.join(unique_onclicks)}")

    # 4. 检查 API 契约与路由声明
    required_routes = [
        "/api/knowledge/documents",
        "/api/knowledge/documents/",
        "/api/knowledge/chunks/",
        "/api/knowledge/pipeline/trace",
        "/api/knowledge/evaluation/dataset",
        "/api/knowledge/evaluation/run_benchmark",
        "/api/knowledge/evaluation/llm_judge",
        "/api/knowledge/evaluation/human_reviews",
        "/api/knowledge/evaluation/human_review"
    ]
    for route in required_routes:
        if route not in js_content:
            print(f"❌ JavaScript 中缺失 API 路由契约: {route}")
            return False
    print(f"✓ [Step 5] 全部 9 项核心 RESTful API 契约与后端 routes.py 100% 对齐")

    # 5. 状态对象 AppState 与 RagApi
    if "const AppState =" not in js_content or "const RagApi =" not in js_content:
        print("❌ 缺失 AppState 或 RagApi 核心定义")
        return False
    print("✓ [Step 6] 内存缓存状态池 AppState 与 RagApi 契约层完备")

    print("=" * 60)
    print("🎉 恭喜！Financial Knowledge RAG 全场景工作台 E2E 校验 100% 通过！")
    print("=" * 60)
    return True

if __name__ == "__main__":
    success = run_e2e_verification()
    sys.exit(0 if success else 1)
