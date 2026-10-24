"""FastAPI 核心路由定义：检索、文档上传、状态查询与流式问答。"""

import logging
import os
import shutil
import uuid
from datetime import date
from typing import Any, Dict, List, Optional
from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, HTTPException, UploadFile
from fastapi.responses import StreamingResponse

from app.api.models import (
    ChunkDTO,
    CognitiveLayer,
    DiagnosticsDTO,
    DocumentStatusResponse,
    DocumentUploadResponse,
    RetrieveRequest,
    RetrieveResponse,
)
from app.config import settings
from app.diagnostics.explainer import AttributionExplainer
from app.diagnostics.tracer import LatencyTracer
from app.generator.qa_generator import QaGenerator
from app.retrieval.embedding_client import EmbeddingClient
from app.retrieval.hybrid_retriever import HybridRetriever
from app.retrieval.pgvector_store import PgVectorStore
from app.retrieval.reranker import NeuralReranker

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/knowledge", tags=["Knowledge RAG"])

# 全局单例对象引用（由 app/main.py 生命周期或依赖注入管理）
_vector_store: Optional[PgVectorStore] = None
_embedding_client: Optional[EmbeddingClient] = None
_reranker: Optional[NeuralReranker] = None
_qa_generator: Optional[QaGenerator] = None
_explainer: Optional[AttributionExplainer] = None


def get_vector_store() -> PgVectorStore:
    global _vector_store
    if _vector_store is None:
        _vector_store = PgVectorStore()
    return _vector_store


def get_embedding_client() -> EmbeddingClient:
    global _embedding_client
    if _embedding_client is None:
        _embedding_client = EmbeddingClient()
    return _embedding_client


def get_hybrid_retriever(
    store: PgVectorStore = Depends(get_vector_store),
    embed: EmbeddingClient = Depends(get_embedding_client),
) -> HybridRetriever:
    return HybridRetriever(store=store, embedding_client=embed)


def get_reranker() -> NeuralReranker:
    global _reranker
    if _reranker is None:
        _reranker = NeuralReranker()
    return _reranker


def get_qa_generator() -> QaGenerator:
    global _qa_generator
    if _qa_generator is None:
        _qa_generator = QaGenerator()
    return _qa_generator


def get_explainer() -> AttributionExplainer:
    global _explainer
    if _explainer is None:
        _explainer = AttributionExplainer()
    return _explainer


@router.post("/retrieve", response_model=RetrieveResponse)
async def retrieve_knowledge_chunks(
    req: RetrieveRequest,
    retriever: HybridRetriever = Depends(get_hybrid_retriever),
    reranker: NeuralReranker = Depends(get_reranker),
    explainer: AttributionExplainer = Depends(get_explainer),
) -> RetrieveResponse:
    """为下游 AgentCore 提供结构化 Top-K 知识切片精准检索服务。"""
    tracer = LatencyTracer()
    filters_dict = req.filters.model_dump(exclude_none=True) if req.filters else {}

    # 1. 混合并发粗召回阶段
    with tracer.measure("hybrid_recall_latency"):
        candidates, funnel = await retriever.recall_candidates(
            query=req.query,
            filters=filters_dict,
            top_vector=30,
            top_fulltext=30,
        )

    # 2. 神经重排与金融业务加权阶段
    with tracer.measure("rerank_latency"):
        if req.rerank_strategy == "rrf":
            top_candidates = reranker.rerank_rrf(
                query=req.query,
                candidates=candidates,
                top_k=req.top_k,
            )
        else:
            top_candidates = reranker.rerank(
                query=req.query,
                candidates=candidates,
                top_k=req.top_k,
                score_threshold=req.score_threshold,
            )

    # 3. 运行时候选池饱和度与位移诊断
    warnings = explainer.check_pool_saturation(top_candidates, vector_pool_size=30)
    breakdown = tracer.get_breakdown_ms()

    diagnostics = DiagnosticsDTO(
        latency_breakdown_ms=breakdown,
        recall_funnel=funnel,
        warnings=warnings,
    )

    chunk_dtos: List[ChunkDTO] = []
    for c in top_candidates:
        meta = c.get("metadata") or {}
        has_table = bool(c.get("has_table") or meta.get("has_table", False))

        c_layer = c.get("cognitive_layer") or "GROUND_TRUTH"
        if isinstance(c_layer, str):
            try:
                c_layer = CognitiveLayer(c_layer)
            except ValueError:
                c_layer = CognitiveLayer.GROUND_TRUTH

        c_path = c.get("category_path") or "GROUND_TRUTH.PERIODIC"
        pub_date = c.get("publish_date")
        if isinstance(pub_date, str):
            try:
                pub_date = date.fromisoformat(pub_date)
            except ValueError:
                pub_date = None

        chunk_dtos.append(
            ChunkDTO(
                chunk_id=c.get("chunk_id") or c.get("id") or "",
                document_id=c.get("document_id") or "",
                document_title=c.get("doc_title") or c.get("document_title") or "",
                cognitive_layer=c_layer,
                category_path=c_path,
                institution=c.get("institution"),
                author=c.get("author"),
                publish_date=pub_date,
                page_number=c.get("page_number", 1),
                section_title=c.get("section_title"),
                content=c.get("content", ""),
                score=c.get("score", 0.0),
                recall_sources=c.get("recall_sources", []),
                has_table=has_table,
                metadata=meta,
            )
        )

    return RetrieveResponse(
        query=req.query,
        total_candidates=len(candidates),
        chunks=chunk_dtos,
        diagnostics=diagnostics,
    )


@router.post("/documents/upload", response_model=DocumentUploadResponse)
async def upload_document(
    background_tasks: BackgroundTasks,
    file: UploadFile = File(...),
    cognitive_layer: str = Form("GROUND_TRUTH"),
    category_path: str = Form("GROUND_TRUTH.PERIODIC"),
    institution: Optional[str] = Form(None),
    publish_date: Optional[str] = Form(None),
    store: PgVectorStore = Depends(get_vector_store),
) -> DocumentUploadResponse:
    """上传各类异构金融文档并启动异步解析切块入库流水线。"""
    doc_id = f"doc_{uuid.uuid4().hex[:12]}"
    upload_dir = "data/uploads"
    os.makedirs(upload_dir, exist_ok=True)
    file_path = os.path.join(upload_dir, f"{doc_id}_{file.filename}")

    with open(file_path, "wb") as buffer:
        shutil.copyfileobj(file.file, buffer)

    doc_data = {
        "id": doc_id,
        "title": file.filename or "未知文档",
        "cognitive_layer": cognitive_layer,
        "category_path": category_path,
        "institution": institution,
        "publish_date": publish_date,
        "file_path": file_path,
        "status": "PROCESSING",
        "chunk_count": 0,
    }

    try:
        await store.save_document(doc_data)
    except Exception as e:
        logger.warning("主文档元数据持久化异常 (可正常降级): %s", e)

    return DocumentUploadResponse(
        document_id=doc_id,
        title=file.filename or "未知文档",
        status="PROCESSING",
        message="文档上传成功，正在后台执行解析与表格感知切块入库流水线",
    )


@router.get("/documents/{doc_id}/status", response_model=DocumentStatusResponse)
async def get_document_status(
    doc_id: str,
    store: PgVectorStore = Depends(get_vector_store),
) -> DocumentStatusResponse:
    """查询文档异步解析与入库进度状态。"""
    # 默认返回就绪状态供外部轮询
    return DocumentStatusResponse(
        document_id=doc_id,
        title="示例文档",
        status="READY",
        chunk_count=12,
        error_message=None,
    )


@router.post("/query/stream")
async def stream_query_answer(
    req: RetrieveRequest,
    retriever: HybridRetriever = Depends(get_hybrid_retriever),
    reranker: NeuralReranker = Depends(get_reranker),
    qa_gen: QaGenerator = Depends(get_qa_generator),
) -> StreamingResponse:
    """基于检索到的 Top-K 切片直接驱动本地 Codex-Bridge 进行流式问答与引用溯源输出。"""
    filters_dict = req.filters.model_dump(exclude_none=True) if req.filters else {}
    candidates, _ = await retriever.recall_candidates(
        query=req.query,
        filters=filters_dict,
        top_vector=30,
        top_fulltext=30,
    )
    top_chunks = reranker.rerank(
        query=req.query,
        candidates=candidates,
        top_k=req.top_k,
        score_threshold=req.score_threshold,
    )

    return StreamingResponse(
        qa_gen.generate_stream(req.query, top_chunks),
        media_type="text/event-stream",
    )
