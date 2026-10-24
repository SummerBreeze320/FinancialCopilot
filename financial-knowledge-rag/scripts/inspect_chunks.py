"""切片透视与预览脚本：查看任意金融文档被切分后的具体形态。"""

import argparse
import json
import os
import sys

# Windows PowerShell 终端 UTF-8 编码适配
if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8")

# 将项目根目录加入 sys.path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.ingest.parser_registry import ParserRegistry
from app.ingest.splitter import TableAwareSplitter


def inspect_document_chunks(
    file_path: str,
    chunk_size: int = 700,
    overlap: int = 100,
    export_json: bool = False,
) -> None:
    """解析并打印切片后的详细结构与内容。"""
    if not os.path.exists(file_path):
        print(f"❌ 错误：指定的文件不存在: {file_path}")
        return

    print("=" * 80)
    print(f"📄 正在解析并切分文档: {os.path.abspath(file_path)}")
    print(f"⚙️ 切块策略参数: 目标切片大小 = {chunk_size} 字符, 滑动重叠 = {overlap} 字符")
    print("=" * 80)

    # 1. 初始化切块器与解析注册中心
    splitter = TableAwareSplitter(chunk_size=chunk_size, chunk_overlap=overlap)
    registry = ParserRegistry(splitter=splitter)

    # 2. 自动格式分发与切分
    chunks = registry.parse_file(file_path)
    cognitive_layer, category_path = registry.infer_category(os.path.basename(file_path))

    print(f"📌 推断认知层级: {cognitive_layer.value}")
    print(f"🏷️ 推断分类路径: {category_path}")
    print(f"✂️ 切分完成，共生成 {len(chunks)} 个知识切片 (Chunks)。\n")

    json_records = []

    for idx, c in enumerate(chunks, start=1):
        table_flag = "📊 [包含表格]" if c.has_table else "📝 [纯文本]"
        headers = c.metadata.get("table_headers") if c.metadata else None
        print(f"┌── 【Chunk #{idx:02d}】 {table_flag} ───────────────")
        print(f"│ 物理页码: {c.page_number} | 章节大纲: {c.section_title or '正文'}")
        print(f"│ 字符长度: {len(c.content)} 字符")
        if c.has_table and headers:
            print(f"│ 挂顶表头: {headers}")
        print("├──────────────────────────────────────────────────────")
        # 逐行缩进打印切片原文
        for line in c.content.splitlines():
            print(f"│ {line}")
        print("└──────────────────────────────────────────────────────\n")

        if export_json:
            json_records.append({
                "chunk_index": idx,
                "page_number": c.page_number,
                "section_title": c.section_title,
                "char_length": len(c.content),
                "has_table": c.has_table,
                "table_headers": headers,
                "content": c.content,
            })

    if export_json:
        out_name = f"{os.path.splitext(file_path)[0]}_chunks.json"
        with open(out_name, "w", encoding="utf-8") as f:
            json.dump(json_records, f, ensure_ascii=False, indent=2)
        print(f"💾 已将全量切片导出至 JSON 文件: {out_name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="金融文档切片效果可视化透视工具")
    parser.add_argument("--file", "-f", required=True, help="待切分的文档路径 (.md, .docx, .xlsx, .pdf)")
    parser.add_argument("--chunk-size", "-s", type=int, default=700, help="切片字符阈值 (默认 700)")
    parser.add_argument("--overlap", "-o", type=int, default=100, help="滑动重叠字符数 (默认 100)")
    parser.add_argument("--export-json", action="store_true", help="是否额外导出为 JSON 文件")

    args = parser.parse_args()
    inspect_document_chunks(
        file_path=args.file,
        chunk_size=args.chunk_size,
        overlap=args.overlap,
        export_json=args.export_json,
    )
