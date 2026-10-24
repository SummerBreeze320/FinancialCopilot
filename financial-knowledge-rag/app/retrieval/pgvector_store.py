"""PostgreSQL 16 + pgvector 存储引擎与异步检索适配器。"""

import json
import logging
from typing import Any, Dict, List, Optional, Tuple
import asyncpg

from app.config import settings

logger = logging.getLogger(__name__)


class PgVectorStore:
    """PostgreSQL pgvector 知识切片与文档持久化、向量/全文检索存储层。"""

    def __init__(
        self,
        db_url: Optional[str] = None,
        pool: Optional[asyncpg.Pool] = None,
    ) -> None:
        """初始化存储引擎。

        :param db_url: 数据库连接串，默认从配置读取
        :param pool: 可选的已有 asyncpg 连接池
        """
        self.db_url = db_url or settings.database_url
        self.pool = pool

    async def init_pool(self) -> None:
        """异步初始化数据库连接池。"""
        if self.pool is None:
            self.pool = await asyncpg.create_pool(
                self.db_url,
                min_size=2,
                max_size=10,
            )

    async def close(self) -> None:
        """优雅关闭数据库连接池。"""
        if self.pool is not None:
            await self.pool.close()
            self.pool = None

    def _build_filter_clauses(
        self,
        stock_codes: Optional[List[str]] = None,
        fund_codes: Optional[List[str]] = None,
        cognitive_layers: Optional[List[str]] = None,
        category_paths: Optional[List[str]] = None,
        doc_types: Optional[List[str]] = None,
        institutions: Optional[List[str]] = None,
        publish_date_start: Optional[str] = None,
        publish_date_end: Optional[str] = None,
        start_param_idx: int = 1,
    ) -> Tuple[str, List[Any]]:
        """构建动态 SQL 过滤子句与参数列表。

        :return: (WHERE 子句字符串, 参数绑定列表)
        """
        clauses: List[str] = []
        params: List[Any] = []
        idx = start_param_idx

        if stock_codes:
            clauses.append(f"c.stock_codes && ${idx}")
            params.append(stock_codes)
            idx += 1

        if fund_codes:
            clauses.append(f"c.fund_codes && ${idx}")
            params.append(fund_codes)
            idx += 1

        if cognitive_layers:
            clauses.append(f"c.cognitive_layer = ANY(${idx})")
            params.append(cognitive_layers)
            idx += 1

        if category_paths:
            has_wildcard = any("%" in p for p in category_paths)
            if has_wildcard:
                clauses.append(f"c.category_path LIKE ANY(${idx})")
            else:
                clauses.append(f"c.category_path = ANY(${idx})")
            params.append(category_paths)
            idx += 1

        if institutions:
            clauses.append(f"d.institution = ANY(${idx})")
            params.append(institutions)
            idx += 1

        if publish_date_start:
            clauses.append(f"d.publish_date >= ${idx}::date")
            params.append(publish_date_start)
            idx += 1

        if publish_date_end:
            clauses.append(f"d.publish_date <= ${idx}::date")
            params.append(publish_date_end)
            idx += 1

        where_sql = " AND ".join(clauses) if clauses else "1=1"
        return where_sql, params

    async def save_document(self, doc_data: Dict[str, Any]) -> None:
        """持久化单篇主文档元数据信息。"""
        if self.pool is None:
            await self.init_pool()

        sql = """
        INSERT INTO knowledge_documents (
            id, title, cognitive_layer, category_path, institution, author,
            publish_date, report_period, is_latest_period, authority_score,
            file_path, file_hash, chunk_count, status, error_message, updated_at
        ) VALUES (
            $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, NOW()
        ) ON CONFLICT (id) DO UPDATE SET
            title = EXCLUDED.title,
            cognitive_layer = EXCLUDED.cognitive_layer,
            category_path = EXCLUDED.category_path,
            chunk_count = EXCLUDED.chunk_count,
            status = EXCLUDED.status,
            error_message = EXCLUDED.error_message,
            updated_at = NOW();
        """
        async with self.pool.acquire() as conn:
            await conn.execute(
                sql,
                doc_data.get("id"),
                doc_data.get("title", ""),
                doc_data.get("cognitive_layer", "GROUND_TRUTH"),
                doc_data.get("category_path", "GROUND_TRUTH.PERIODIC"),
                doc_data.get("institution"),
                doc_data.get("author"),
                doc_data.get("publish_date"),
                doc_data.get("report_period"),
                doc_data.get("is_latest_period", False),
                doc_data.get("authority_score", 1.0),
                doc_data.get("file_path"),
                doc_data.get("file_hash"),
                doc_data.get("chunk_count", 0),
                doc_data.get("status", "SUCCESS"),
                doc_data.get("error_message"),
            )

    async def save_chunks(
        self,
        chunks: List[Dict[str, Any]],
        embeddings: List[List[float]],
    ) -> None:
        """批量保存切片文本、向量以及元数据。"""
        if self.pool is None:
            await self.init_pool()

        sql = """
        INSERT INTO knowledge_chunks (
            id, document_id, chunk_index, page_number, section_title, content,
            content_length, embedding, cognitive_layer, category_path,
            stock_codes, fund_codes, metadata, tsv_content
        ) VALUES (
            $1, $2, $3, $4, $5, $6, $7, $8::vector, $9, $10, $11, $12, $13::jsonb,
            to_tsvector('simple', $6)
        ) ON CONFLICT (id) DO NOTHING;
        """
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                for chunk, emb in zip(chunks, embeddings):
                    emb_str = f"[{','.join(f'{x:.6f}' for x in emb)}]"
                    metadata_json = json.dumps(chunk.get("metadata", {}), ensure_ascii=False)
                    await conn.execute(
                        sql,
                        chunk.get("id"),
                        chunk.get("document_id"),
                        chunk.get("chunk_index", 0),
                        chunk.get("page_number", 1),
                        chunk.get("section_title"),
                        chunk.get("content", ""),
                        len(chunk.get("content", "")),
                        emb_str,
                        chunk.get("cognitive_layer", "GROUND_TRUTH"),
                        chunk.get("category_path", "GROUND_TRUTH.PERIODIC"),
                        chunk.get("stock_codes", []),
                        chunk.get("fund_codes", []),
                        metadata_json,
                    )

    async def vector_search(
        self,
        query_embedding: List[float],
        top_k: int = 30,
        filters: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        """基于 pgvector 余弦距离的 Top-K 向量语义召回。"""
        if self.pool is None:
            await self.init_pool()

        emb_str = f"[{','.join(f'{x:.6f}' for x in query_embedding)}]"
        where_sql, filter_params = self._build_filter_clauses(
            stock_codes=filters.get("stock_codes") if filters else None,
            fund_codes=filters.get("fund_codes") if filters else None,
            cognitive_layers=filters.get("cognitive_layers") if filters else None,
            category_paths=filters.get("category_paths") if filters else None,
            institutions=filters.get("institutions") if filters else None,
            publish_date_start=filters.get("publish_date_start") if filters else None,
            publish_date_end=filters.get("publish_date_end") if filters else None,
            start_param_idx=2,
        )

        sql = f"""
        SELECT 
            c.id, c.document_id, c.chunk_index, c.page_number, c.section_title,
            c.content, c.content_length, c.cognitive_layer, c.category_path,
            c.stock_codes, c.fund_codes, c.metadata,
            (1 - (c.embedding <=> $1::vector)) AS vector_score,
            d.title AS doc_title, d.institution, d.publish_date, d.authority_score
        FROM knowledge_chunks c
        LEFT JOIN knowledge_documents d ON c.document_id = d.id
        WHERE {where_sql}
        ORDER BY c.embedding <=> $1::vector ASC
        LIMIT {top_k};
        """

        async with self.pool.acquire() as conn:
            rows = await conn.fetch(sql, emb_str, *filter_params)
            return [dict(row) for row in rows]

    async def fulltext_search(
        self,
        query_text: str,
        top_k: int = 30,
        filters: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        """基于 PostgreSQL tsvector 的全文 BM25 变体关键词召回。"""
        if self.pool is None:
            await self.init_pool()

        where_sql, filter_params = self._build_filter_clauses(
            stock_codes=filters.get("stock_codes") if filters else None,
            fund_codes=filters.get("fund_codes") if filters else None,
            cognitive_layers=filters.get("cognitive_layers") if filters else None,
            category_paths=filters.get("category_paths") if filters else None,
            institutions=filters.get("institutions") if filters else None,
            publish_date_start=filters.get("publish_date_start") if filters else None,
            publish_date_end=filters.get("publish_date_end") if filters else None,
            start_param_idx=2,
        )

        sql = f"""
        SELECT 
            c.id, c.document_id, c.chunk_index, c.page_number, c.section_title,
            c.content, c.content_length, c.cognitive_layer, c.category_path,
            c.stock_codes, c.fund_codes, c.metadata,
            COALESCE(ts_rank_cd(c.tsv_content, plainto_tsquery('simple', $1)), 0.0) AS text_score,
            d.title AS doc_title, d.institution, d.publish_date, d.authority_score
        FROM knowledge_chunks c
        LEFT JOIN knowledge_documents d ON c.document_id = d.id
        WHERE ({where_sql}) AND (c.tsv_content @@ plainto_tsquery('simple', $1) OR c.content ILIKE '%' || $1 || '%')
        ORDER BY text_score DESC
        LIMIT {top_k};
        """

        async with self.pool.acquire() as conn:
            rows = await conn.fetch(sql, query_text, *filter_params)
            return [dict(row) for row in rows]
