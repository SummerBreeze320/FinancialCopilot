"""FastAPI 微服务主入口与应用生命周期管理。"""

from contextlib import asynccontextmanager
import logging
from typing import AsyncGenerator
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import get_vector_store, router
from app.config import settings

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] [%(name)s] %(message)s",
)
logger = logging.getLogger("rag-service")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """应用程序生命周期管理：启动时初始化数据库连接池，退出时清理释放资源。"""
    logger.info("正在启动 financial-knowledge-rag 微服务 (端口: %d)...", settings.port)
    store = get_vector_store()
    try:
        await store.init_pool()
        logger.info("PostgreSQL pgvector 连接池初始化成功。")
    except Exception as e:
        logger.warning("PostgreSQL 连接池初始化未就绪 (将在首次请求时重试或处于降级模式): %s", e)

    yield

    logger.info("正在关闭 financial-knowledge-rag 微服务连接池...")
    try:
        await store.close()
    except Exception as e:
        logger.warning("关闭连接池异常: %s", e)


app = FastAPI(
    title="financial-knowledge-rag",
    version="1.0.0",
    description="金融知识库 RAG 高性能微服务，负责多源异构文档解析入库、双路并发召回与神经重排",
    lifespan=lifespan,
)

# 允许跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 挂载路由
app.include_router(router)


@app.get("/health")
async def health_check() -> dict:
    """服务健康检查接口。"""
    return {
        "status": "UP",
        "service": "financial-knowledge-rag",
        "port": settings.port,
        "device": settings.reranker_device,
        "embedding_model": settings.embedding_model,
        "reranker_model": settings.reranker_model_path,
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host="127.0.0.1",
        port=settings.port,
        reload=False,
    )
