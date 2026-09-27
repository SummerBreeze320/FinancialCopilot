from __future__ import annotations

from contextlib import asynccontextmanager
import asyncio
import logging
import uuid

import httpx
from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api import entities, index, schema
from app.api.dependencies import ServiceUnavailableError, get_repository, runtime
from app.api.entities import EntityNotFoundError
from app.api.index import JobNotFoundError, UnauthorizedError
from app.catalog.loader import CatalogLoader
from app.config import get_settings
from app.core.embedding import EmbeddingClient
from app.core.exact_matcher import ExactMatcher
from app.core.mention_generator import MentionGenerator
from app.core.pipeline import IndexNotReadyError, RecognitionPipeline
from app.core.reranker import Reranker
from app.core.resolver import Resolver
from app.db.session import create_engine_and_session
from app.db.repository import EntityRepository
from app.indexing.service import IndexingService, RebuildAlreadyRunning


logger = logging.getLogger(__name__)


def _request_id(request: Request) -> str:
    return request.headers.get("X-Request-ID") or str(uuid.uuid4())


def _error(request: Request, status: int, code: str, message: str, details: dict | None = None):
    return JSONResponse(
        status_code=status,
        content={"error": {
            "code": code,
            "message": message,
            "request_id": _request_id(request),
            "details": details or {},
        }},
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    if app.dependency_overrides:
        yield
        return
    settings = get_settings()
    if settings.entity_admin_token in {"", "change-me", "replace-with-a-random-secret"}:
        raise RuntimeError("ENTITY_ADMIN_TOKEN must be set to a non-placeholder secret")
    engine = None
    embedding_http = None
    llm_http = None
    try:
        engine, sessions = create_engine_and_session(settings.database_url)
        repository = EntityRepository(sessions)
        embedding_http = httpx.AsyncClient(base_url=settings.embedding_base_url,
                                           timeout=settings.request_timeout_seconds)
        llm_http = httpx.AsyncClient(base_url=settings.llm_base_url,
                                     timeout=settings.request_timeout_seconds)
        runtime.embedding_http = embedding_http
        runtime.reranker_http = llm_http
        embedding = EmbeddingClient(embedding_http, settings.embedding_model, settings.embedding_dim)
        reranker = Reranker(llm_http, settings.llm_model, settings.llm_api_key)
        try:
            records = await repository.list_active_entities()
        except Exception as exc:
            logger.warning("Could not load active entity index: %s", exc)
            records = ()
            runtime.startup_error = str(exc)
        runtime.repository = repository
        pipeline = RecognitionPipeline(
            matcher=ExactMatcher.build(records),
            mention_generator=MentionGenerator(settings.max_mention_spans),
            embedding=embedding,
            repository=repository,
            reranker=reranker,
            resolver=Resolver(
                settings.return_min_confidence,
                settings.resolved_min_confidence,
                settings.resolved_min_margin,
                settings.ambiguous_max_candidates,
            ),
            vector_min_similarity=settings.vector_min_similarity,
        )
        runtime.pipeline = pipeline

        async def activate_catalog(version_id, document, refreshed_records):
            await pipeline.activate_catalog(
                version_id, document.version, refreshed_records, repository.activate_version
            )

        runtime.indexing_service = IndexingService(
            repository, CatalogLoader(), embedding, activator=activate_catalog
        )
        yield
    finally:
        runtime.pipeline = None
        runtime.repository = None
        runtime.indexing_service = None
        runtime.embedding_http = None
        runtime.reranker_http = None
        if embedding_http:
            await embedding_http.aclose()
        if llm_http:
            await llm_http.aclose()
        if engine:
            await engine.dispose()


app = FastAPI(
    title="FinancialCopilot Entity Service",
    description="Catalog-backed financial entity recognition with pgvector RAG",
    version="0.1.0",
    lifespan=lifespan,
)
app.include_router(index.router)
app.include_router(entities.router)
app.include_router(schema.router)


@app.exception_handler(RequestValidationError)
async def validation_error(request: Request, exc: RequestValidationError):
    return _error(request, 422, "VALIDATION_ERROR", "Request validation failed", {"errors": exc.errors()})


@app.exception_handler(EntityNotFoundError)
async def entity_not_found(request: Request, exc: EntityNotFoundError):
    return _error(request, 404, "ENTITY_NOT_FOUND", f"Entity not found: {exc}")


@app.exception_handler(JobNotFoundError)
async def job_not_found(request: Request, exc: JobNotFoundError):
    return _error(request, 404, "JOB_NOT_FOUND", f"Index job not found: {exc}")


@app.exception_handler(UnauthorizedError)
async def unauthorized(request: Request, exc: UnauthorizedError):
    return _error(request, 401, "UNAUTHORIZED", str(exc))


@app.exception_handler(RebuildAlreadyRunning)
async def rebuild_running(request: Request, exc: RebuildAlreadyRunning):
    return _error(request, 409, "REBUILD_ALREADY_RUNNING", str(exc))


@app.exception_handler(IndexNotReadyError)
async def index_not_ready(request: Request, exc: IndexNotReadyError):
    return _error(request, 503, "INDEX_NOT_READY", str(exc))


@app.exception_handler(ServiceUnavailableError)
async def service_unavailable(request: Request, exc: ServiceUnavailableError):
    return _error(request, 503, "DEPENDENCY_UNAVAILABLE", str(exc))


@app.get("/health", tags=["health"])
async def health(repository=Depends(get_repository)):
    try:
        version = await repository.get_active_version()
        database_status = "ok"
    except Exception:
        version = None
        database_status = "unavailable"
    async def probe(client):
        if client is None:
            return "configured"
        try:
            response = await asyncio.wait_for(client.get("/models"), timeout=2.0)
            return "ok" if response.is_success else "unavailable"
        except Exception:
            return "unavailable"

    embedding_status, reranker_status = await asyncio.gather(
        probe(runtime.embedding_http), probe(runtime.reranker_http)
    )
    if database_status != "ok" or not version:
        status = "unavailable"
    elif "unavailable" in {embedding_status, reranker_status}:
        status = "degraded"
    else:
        status = "ok"
    return {
        "status": status,
        "service": "entity-service",
        "active_catalog_version": version.catalog_version if version else None,
        "entity_count": version.entity_count if version else 0,
        "dependencies": {
            "database": database_status,
            "active_index": "ok" if version else "unavailable",
            "embedding": embedding_status,
            "reranker": reranker_status,
        },
    }


@app.get("/", tags=["discovery"])
async def root():
    return {
        "service": "FinancialCopilot Entity Service",
        "version": "0.1.0",
        "endpoints": {
            "recognize": "/v1/entities/recognize",
            "entity": "/v1/entities/{entity_id}",
            "rebuild": "/v1/entities/index/rebuild",
            "schema": "/v1/schema/recognition-response",
            "health": "/health",
            "docs": "/docs",
        },
    }


if __name__ == "__main__":
    import uvicorn
    settings = get_settings()
    uvicorn.run("app.main:app", host=settings.host, port=settings.port, reload=True)
