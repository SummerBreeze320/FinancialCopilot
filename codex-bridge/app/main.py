import json
import time
import uuid

from fastapi import FastAPI, HTTPException

from app.config import Settings
from app.models import ChatCompletionRequest
from app.runner import AgyCliRunner, CodexCliRunner


def build_runner(settings: Settings):
    common = {
        "timeout_seconds": settings.timeout_seconds,
        "max_concurrency": settings.max_concurrency,
    }
    if settings.backend == "agy":
        return AgyCliRunner(command=settings.agy_command, **common)
    if settings.backend == "codex":
        return CodexCliRunner(command=settings.codex_command, **common)
    raise ValueError(f"unsupported backend: {settings.backend}")


def create_app(runner=None) -> FastAPI:
    settings = Settings()
    backend = runner or build_runner(settings)
    app = FastAPI(title="Local LLM CLI Bridge", version="0.2.0")

    @app.get("/health")
    async def health():
        return {"status": "ok", "backend": backend.backend_name}

    @app.post("/v1/chat/completions")
    async def chat_completions(request: ChatCompletionRequest):
        try:
            result = await backend.run(
                messages=[message.model_dump() for message in request.messages],
                model=request.model,
                reasoning_effort=request.reasoning_effort,
                response_format=request.response_format.model_dump() if request.response_format else None,
            )
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"LLM backend execution failed: {exc}") from exc

        content = (
            json.dumps(result, ensure_ascii=False, separators=(",", ":"))
            if isinstance(result, (dict, list))
            else str(result)
        )

        return {
            "id": f"chatcmpl-{uuid.uuid4().hex}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": request.model,
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": content,
                },
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }

    return app


app = create_app()
