"""单个金融文档摄取与入库脚本。

支持 PDF, Word, Excel, Markdown 文档解析、表格感知切块、Ollama 向量化并持久化至 PostgreSQL。
"""

import asyncio
import os
import re
import sys
from pathlib import Path

from app.api.models import CognitiveLayer
from app.ingest.parser_registry import ParserRegistry
from app.retrieval.embedding_client import EmbeddingClient
from app.retrieval.pgvector_store import PgVectorStore


async def ingest_file(file_path: str, title: str | None = None) -> dict:
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"文件不存在: {file_path}")

    filename = path.name
    doc_title = title or filename
    doc_id = f"doc_{abs(hash(str(path))) % 10000000:07d}"

    print(f"============================================================")
    print(f"[INGEST] 正在处理文档: {filename}")
    print(f"[INGEST] 文档 ID: {doc_id}")
    print(f"============================================================")

    # 1. 格式分发与切块
    registry = ParserRegistry()
    parser = registry.get_parser_for_extension(path.suffix)
    if not parser:
        raise ValueError(f"不支持的文件扩展名: {path.suffix}")

    chunks = parser.parse_file(str(path))
    table_count = sum(1 for c in chunks if c.has_table)
    print(f"[1/4] 文档切分完成: {len(chunks)} 个切片 (含结构化表格: {table_count} 个)")

    # 2. 自动推断认知层与分类
    layer, category_path = registry.infer_category(filename)
    print(f"[2/4] 认知层推断: {layer.value} | 体系分类: {category_path}")

    texts = [c.content.strip() for c in chunks]
    chunk_dicts = []
    for idx, c in enumerate(chunks):
        content = c.content.strip()
        # 提取 6 位基金/股票代码
        funds = list(set(re.findall(r"\b(320007|025333|\d{6})\b", content)))
        c_meta = dict(c.metadata)
        c_meta["has_table"] = c.has_table
        chunk_dicts.append({
            "id": f"{doc_id}_c{idx:03d}",
            "document_id": doc_id,
            "chunk_index": idx,
            "page_number": c.metadata.get("page_number", 1),
            "section_title": c.metadata.get("section_title") or f"P.{c.metadata.get('page_number', 1)}",
            "content": content,
            "cognitive_layer": layer.value,
            "category_path": category_path,
            "stock_codes": [],
            "fund_codes": funds,
            "metadata": c_meta,
        })

    # 3. 向量化计算
    print(f"[3/4] 正在调用本地 Ollama 生成 {len(texts)} 个 1024 维切片嵌入向量...")
    embed = EmbeddingClient()
    embeddings = await embed.embed_texts(texts)
    print(f"      向量化生成完毕 (维度: {len(embeddings[0])})")

    # 4. 写入 PostgreSQL pgvector 数据库
    print(f"[4/4] 正在写入 PostgreSQL 16 (HNSW 向量 + GIN 全文检索)...")
    store = PgVectorStore()
    await store.init_pool()

    doc_meta = {
        "id": doc_id,
        "title": doc_title,
        "cognitive_layer": layer.value,
        "category_path": category_path,
        "institution": "诺安基金管理有限公司" if "诺安" in filename else None,
        "publish_date": "2026-07-21" if "2026" in filename else None,
        "file_path": str(path.absolute()),
        "status": "READY",
        "chunk_count": len(chunks),
    }

    await store.save_document(doc_meta)
    await store.save_chunks(chunk_dicts, embeddings)
    await store.close()

    print(f"============================================================")
    print(f"[INGEST] 摄取入库成功！共持久化 {len(chunks)} 个切片至知识库。")
    print(f"============================================================")
    return doc_meta


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else r"D:\Users\longlong\Downloads\诺安成长混合型证券投资基金2026 年第 2 季度报告2026 年 06 月 30 日.pdf"
    asyncio.run(ingest_file(target))
