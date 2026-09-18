"""FinancialCopilot 记忆服务 — FastAPI 入口"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.config import settings
from app.core.graphiti_init import init_graphiti_indices, close_graphiti
from app.engine.short_term import short_term_engine
from app.api import sessions, memory, profile

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Memory service starting up...")
    await init_graphiti_indices()
    logger.info("Memory service ready on port %d", settings.server.port)
    yield
    logger.info("Memory service shutting down...")
    await short_term_engine.close()
    await close_graphiti()
    logger.info("Memory service stopped.")


app = FastAPI(
    title="FinancialCopilot Memory Service",
    description="基于 Graphiti 的时序知识图谱记忆服务 — 短期记忆 + 长期记忆 + 用户画像",
    version="0.1.0",
    lifespan=lifespan,
)

app.include_router(sessions.router)
app.include_router(memory.router)
app.include_router(profile.router)


@app.get("/health")
async def health():
    return {"status": "ok", "service": "memory-service"}


@app.get("/")
async def root():
    return {
        "service": "FinancialCopilot Memory Service",
        "version": "0.1.0",
        "endpoints": {
            "sessions": "/v1/sessions",
            "memory_search": "/v1/memory/search",
            "profile": "/v1/users/{user_id}/profile",
            "health": "/health",
            "docs": "/docs",
        },
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host=settings.server.host,
        port=settings.server.port,
        reload=True,
    )
