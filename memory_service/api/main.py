from contextlib import asynccontextmanager
from fastapi import FastAPI
from memory_service.api.routes.recall import router as recall_router
from memory_service.api.routes.pipeline import router as pipeline_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 服务启动初始化
    yield
    # 服务关闭清理


def create_app() -> FastAPI:
    app = FastAPI(
        title="Financial Copilot Long-Term Memory Service",
        description="High-reliability, deterministic long-term memory engine and online reader service",
        version="1.0.0",
        lifespan=lifespan,
    )

    app.include_router(recall_router, prefix="/api/v1/memory", tags=["Online Memory Reader"])
    app.include_router(pipeline_router, prefix="/api/v1/memory", tags=["Offline Memory Pipeline"])

    @app.get("/health", tags=["Health"])
    async def health():
        return {"status": "ok", "service": "memory_service"}

    return app


app = create_app()
