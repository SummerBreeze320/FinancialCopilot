from fastapi import FastAPI

from app.api.schema import router as schema_router
from app.api.commands import router as command_router
from app.pipeline.orchestrator import ScreeningOrchestrator
from app.config import Settings
from app.runtime import build_production_compiler


def create_app(compiler=None) -> FastAPI:
    """创建基金筛选 HTTP 应用。"""
    app = FastAPI(title="Fund Screening Service", version="0.1.0")
    app.state.orchestrator = ScreeningOrchestrator(compiler)
    app.include_router(schema_router)
    app.include_router(command_router)

    @app.get("/v1/fund-screening/health")
    async def health() -> dict[str, object]:
        return {
            "service": "fund-screening-service",
            "dependencies": {
                "catalog": "configured",
                "pgvector": "configured",
                "redis": "configured",
                "llm": "configured",
            },
        }

    return app


def create_configured_app() -> FastAPI:
    settings = Settings()
    paths_ready = all((settings.metrics_catalog_path, settings.sectors_catalog_path, settings.entity_catalog_path))
    compiler = build_production_compiler(settings) if settings.pipeline_mode == "rag" and paths_ready else None
    configured = create_app(compiler)
    configured.state.pipeline_mode = "rag" if compiler else "mvp"
    return configured


app = create_configured_app()
