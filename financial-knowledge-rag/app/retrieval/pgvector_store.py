"""PostgreSQL 16 + pgvector 存储引擎与异步检索适配器。"""

import json
import logging
from typing import Any, Dict, List, Optional, Tuple
import asyncpg

from app.config import settings

logger = logging.getLogger(__name__)


def _segment_text_jieba(text: str) -> str:
    """使用 jieba 对中文文本进行空格分词，使 PostgreSQL simple 分词字典能有效建立和查询倒排索引。"""
    if not text:
        return ""
    try:
        import jieba
        words = [w.strip() for w in jieba.cut(text) if w.strip()]
        return " ".join(words)
    except Exception:
        return text


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
        document_ids: Optional[List[str]] = None,
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

        if document_ids:
            clauses.append(f"c.document_id = ANY(${idx})")
            params.append(document_ids)
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
        pub_date = doc_data.get("publish_date")
        if isinstance(pub_date, str):
            try:
                from datetime import date
                pub_date = date.fromisoformat(pub_date)
            except Exception:
                pub_date = None

        async with self.pool.acquire() as conn:
            await conn.execute(
                sql,
                doc_data.get("id"),
                doc_data.get("title", ""),
                doc_data.get("cognitive_layer", "GROUND_TRUTH"),
                doc_data.get("category_path", "GROUND_TRUTH.PERIODIC"),
                doc_data.get("institution"),
                doc_data.get("author"),
                pub_date,
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
        """批量保存切片文本、向量以及元数据，并进行中文分词注入 PostgreSQL 全文索引。"""
        if self.pool is None:
            await self.init_pool()

        sql = """
        INSERT INTO knowledge_chunks (
            id, document_id, chunk_index, page_number, section_title, content,
            content_length, embedding, cognitive_layer, category_path,
            stock_codes, fund_codes, metadata, tsv_content
        ) VALUES (
            $1, $2, $3, $4, $5, $6, $7, $8::vector, $9, $10, $11, $12, $13::jsonb,
            to_tsvector('simple', $14)
        ) ON CONFLICT (id) DO UPDATE SET
            section_title = EXCLUDED.section_title,
            content = EXCLUDED.content,
            content_length = EXCLUDED.content_length,
            embedding = EXCLUDED.embedding,
            stock_codes = EXCLUDED.stock_codes,
            fund_codes = EXCLUDED.fund_codes,
            metadata = EXCLUDED.metadata,
            tsv_content = EXCLUDED.tsv_content;
        """
        async with self.pool.acquire() as conn:
            async with conn.transaction():
                for chunk, emb in zip(chunks, embeddings):
                    emb_str = f"[{','.join(f'{x:.6f}' for x in emb)}]"
                    metadata_json = json.dumps(chunk.get("metadata", {}), ensure_ascii=False)
                    sec_title = chunk.get("section_title") or ""
                    content = chunk.get("content") or ""
                    # 强化章节标题：在倒排索引文本中赋予标题更高词频
                    combined = f"{sec_title} {sec_title} {content}"
                    seg_text = _segment_text_jieba(combined)
                    await conn.execute(
                        sql,
                        chunk.get("id"),
                        chunk.get("document_id"),
                        chunk.get("chunk_index", 0),
                        chunk.get("page_number", 1),
                        sec_title,
                        content,
                        len(content),
                        emb_str,
                        chunk.get("cognitive_layer", "GROUND_TRUTH"),
                        chunk.get("category_path", "GROUND_TRUTH.PERIODIC"),
                        chunk.get("stock_codes", []),
                        chunk.get("fund_codes", []),
                        metadata_json,
                        seg_text,
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
            document_ids=filters.get("document_ids") if filters else None,
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
            return [self._format_chunk_row(row) for row in rows]

    def _format_chunk_row(self, row: Any) -> Dict[str, Any]:
        """将数据库行对象转换为标准字段字典，解析 JSON metadata 并提升 has_table / has_chart。"""
        import json
        item = dict(row)
        meta = item.get("metadata")
        if isinstance(meta, str):
            try:
                meta = json.loads(meta)
            except Exception:
                meta = {}
        elif not isinstance(meta, dict):
            meta = {}

        item["metadata"] = meta
        item["has_table"] = bool(meta.get("has_table"))
        item["has_chart"] = bool(meta.get("has_chart"))
        return item

    async def fulltext_search(
        self,
        query_text: str,
        top_k: int = 30,
        filters: Optional[Dict[str, Any]] = None,
    ) -> List[Dict[str, Any]]:
        """基于 PostgreSQL tsvector 中文分词与 BM25Okapi 的双轨关键词与词频打分召回。"""
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
            document_ids=filters.get("document_ids") if filters else None,
            start_param_idx=3,
        )

        import re
        import jieba

        # 过滤无意义停用词，提取有效金融关键词与数值 Token
        stop_words = {"混合型", "证券投资基金", "基金", "关于", "对于", "是多少", "是什么", "以及", "根据", "有的", "请问", "可以", "怎么", "的", "了", "和", "与", "分别", "各是", "分别是"}
        query_tokens = [w.strip() for w in jieba.cut(query_text) if w.strip() and w.strip() not in stop_words]
        
        # 1. 构造空格分词查询，送入 PostgreSQL simple plainto_tsquery
        seg_query_str = " ".join(query_tokens) if query_tokens else _segment_text_jieba(query_text)

        # 2. 构造 OR 连接的 tsquery，用于宽松全量候选召回
        valid_or_tokens = [re.sub(r"[^\w\u4e00-\u9fa5]", "", w) for w in query_tokens if len(w) > 1 or w.isalnum()]
        valid_or_tokens = [w for w in valid_or_tokens if w]
        or_tsquery_str = " | ".join(valid_or_tokens[:12]) if valid_or_tokens else seg_query_str

        # 3. 提取高优金融词用于 ILIKE 与 SQL 动态加权
        priority_words = [t for t in valid_or_tokens if any(k in t for k in ("季度", "份额", "总额", "重仓", "持仓", "前十", "前三", "变动", "资产", "明细", "股票"))]
        tokens = list(dict.fromkeys(priority_words + valid_or_tokens))[:8]

        token_clauses = ""
        # ts_rank_cd 计算中文词项覆盖密度打分 (BM25 变体)
        score_terms = ["COALESCE(ts_rank_cd(c.tsv_content, plainto_tsquery('simple', $1)), 0.0) * 10.0"]
        score_terms.append("COALESCE(ts_rank_cd(c.tsv_content, to_tsquery('simple', $2)), 0.0) * 5.0")
        if tokens:
            token_likes = " OR ".join(f"c.content ILIKE '%{tok}%'" for tok in tokens)
            token_clauses = f" OR ({token_likes})"
            for tok in tokens:
                # 正文命中 +1.5，章节大纲命中 +3.0
                score_terms.append(f"(CASE WHEN c.content ILIKE '%{tok}%' THEN 1.5 ELSE 0.0 END)")
                score_terms.append(f"(CASE WHEN c.section_title ILIKE '%{tok}%' THEN 3.0 ELSE 0.0 END)")

        # 纯封面章节减权惩罚，避免无脑霸榜
        score_terms.append("(CASE WHEN c.section_title = '基本信息' OR c.section_title LIKE '%基本信息%' THEN -4.0 ELSE 0.0 END)")
        dynamic_score_sql = " + ".join(score_terms)

        sql = f"""
        SELECT 
            c.id, c.document_id, c.chunk_index, c.page_number, c.section_title,
            c.content, c.content_length, c.cognitive_layer, c.category_path,
            c.stock_codes, c.fund_codes, c.metadata,
            ({dynamic_score_sql}) AS text_score,
            d.title AS doc_title, d.institution, d.publish_date, d.authority_score
        FROM knowledge_chunks c
        LEFT JOIN knowledge_documents d ON c.document_id = d.id
        WHERE ({where_sql}) AND (
            c.tsv_content @@ plainto_tsquery('simple', $1) 
            OR c.tsv_content @@ to_tsquery('simple', $2)
            OR c.content ILIKE '%' || $1 || '%'{token_clauses}
        )
        ORDER BY text_score DESC, c.chunk_index ASC
        LIMIT {max(top_k * 2, 50)};
        """

        async with self.pool.acquire() as conn:
            try:
                rows = await conn.fetch(sql, seg_query_str, or_tsquery_str, *filter_params)
            except Exception as e:
                logger.warning("PostgreSQL tsquery 执行异常，降级为宽字符匹配: %s", e)
                fallback_sql = f"""
                SELECT 
                    c.id, c.document_id, c.chunk_index, c.page_number, c.section_title,
                    c.content, c.content_length, c.cognitive_layer, c.category_path,
                    c.stock_codes, c.fund_codes, c.metadata,
                    1.0 AS text_score,
                    d.title AS doc_title, d.institution, d.publish_date, d.authority_score
                FROM knowledge_chunks c
                LEFT JOIN knowledge_documents d ON c.document_id = d.id
                WHERE ({where_sql})
                LIMIT {max(top_k * 2, 50)};
                """
                rows = await conn.fetch(fallback_sql, *filter_params)

            items = [self._format_chunk_row(row) for row in rows]

            # 4. 结合 rank_bm25 库进行精准的 BM25Okapi 学术级打分归一化
            if items and query_tokens:
                try:
                    from rank_bm25 import BM25Okapi
                    corpus = [[w for w in jieba.cut(it.get("content", "")) if w.strip()] for it in items]
                    bm25_model = BM25Okapi(corpus)
                    bm25_scores = bm25_model.get_scores(query_tokens)
                    max_bm = max(bm25_scores) if max(bm25_scores) > 0 else 1.0
                    for it, b_score in zip(items, bm25_scores):
                        norm_b = float(b_score) / max_bm
                        it["bm25_score"] = round(float(b_score), 4)
                        # 将 PG 结构化得分与 BM25 词频得分加权融合，确保打分真实非零
                        it["text_score"] = round(float(it.get("text_score", 0.0)) + norm_b * 5.0, 4)
                    items.sort(key=lambda x: x.get("text_score", 0.0), reverse=True)
                except Exception as bm_err:
                    logger.debug("rank_bm25 计算跳过: %s", bm_err)

            return items[:top_k]

    async def list_documents(self) -> List[Dict[str, Any]]:
        """获取所有已持久化主文档列表。"""
        if self.pool is None:
            await self.init_pool()
        sql = """
        SELECT id, title, cognitive_layer, category_path, institution,
               author, publish_date, chunk_count, status, created_at
        FROM knowledge_documents
        ORDER BY created_at DESC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(sql)
            return [dict(row) for row in rows]

    async def get_document_chunks(self, doc_id: str) -> List[Dict[str, Any]]:
        """获取指定主文档下的全部切片列表，按 chunk_index 升序。"""
        if self.pool is None:
            await self.init_pool()
        sql = """
        SELECT id, document_id, chunk_index, page_number, section_title,
               content, content_length, cognitive_layer, category_path,
               stock_codes, fund_codes, metadata, created_at
        FROM knowledge_chunks
        WHERE document_id = $1
        ORDER BY chunk_index ASC;
        """
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(sql, doc_id)
            return [dict(row) for row in rows]

    async def reindex_all_chunks_tsv(self) -> int:
        """重新对数据库中全部切片进行 jieba 中文分词，更新 tsv_content 全文检索字段。"""
        if self.pool is None:
            await self.init_pool()
        logger.info("开始重新对所有切片进行中文分词并刷新 tsv_content...")
        select_sql = "SELECT id, section_title, content FROM knowledge_chunks;"
        update_sql = "UPDATE knowledge_chunks SET tsv_content = to_tsvector('simple', $1) WHERE id = $2;"
        updated_count = 0
        async with self.pool.acquire() as conn:
            rows = await conn.fetch(select_sql)
            async with conn.transaction():
                for row in rows:
                    cid = row["id"]
                    sec = row["section_title"] or ""
                    cnt = row["content"] or ""
                    combined = f"{sec} {sec} {cnt}"
                    seg_text = _segment_text_jieba(combined)
                    await conn.execute(update_sql, seg_text, cid)
                    updated_count += 1
        logger.info("中文分词刷新完成，共更新 %d 条切片全文索引", updated_count)
        return updated_count
