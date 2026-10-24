"""本地目录批量摄取与解析脚本 (Folder Ingestion Runner)。

自动遍历指定目录下的所有 PDF, Excel, Word, Markdown 文档，解析切块并准备入库。
"""

import argparse
import sys
from pathlib import Path
from app.ingest.parser_registry import ParserRegistry


def ingest_folder(folder_path: str) -> None:
    path = Path(folder_path)
    if not path.exists() or not path.is_dir():
        print(f"[ERROR] 目标目录不存在: {folder_path}")
        sys.exit(1)

    registry = ParserRegistry()
    supported_exts = {".pdf", ".xlsx", ".xls", ".docx", ".md", ".txt"}
    files = [f for f in path.rglob("*") if f.is_file() and f.suffix.lower() in supported_exts]

    print(f"============================================================")
    print(f"[INGEST-FOLDER] 扫描到 {len(files)} 个支持的金融文档...")
    print(f"============================================================")

    total_chunks = 0
    for idx, f in enumerate(files, 1):
        print(f"[{idx}/{len(files)}] 正在解析: {f.name} ({f.suffix}) ...")
        try:
            chunks = registry.parse_file(str(f))
            total_chunks += len(chunks)
            table_count = sum(1 for c in chunks if c.has_table)
            print(f"       -> 成功生成 {len(chunks)} 个切片 (其中包含表格: {table_count} 个)")
        except Exception as e:
            print(f"       -> [解析失败]: {e}")

    print(f"============================================================")
    print(f"[INGEST-FOLDER] 处理完成！共生成 {total_chunks} 个金融知识切片。")
    print(f"============================================================")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="批量导入本地文档到知识库")
    parser.add_argument("--path", default="./data/input", help="要扫描的文件夹路径")
    args = parser.parse_args()
    ingest_folder(args.path)
