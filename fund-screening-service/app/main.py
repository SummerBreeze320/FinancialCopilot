from fastapi import FastAPI


def create_app() -> FastAPI:
    """创建基金筛选 HTTP 应用。"""
    app = FastAPI(title="Fund Screening Service", version="0.1.0")

    @app.get("/v1/fund-screening/health")
    async def health() -> dict[str, object]:
        return {
            "service": "fund-screening-service",
            "dependencies": {
                "catalog": "configured",
                "milvus": "configured",
                "redis": "configured",
                "llm": "configured",
            },
        }

    return app


app = create_app()
