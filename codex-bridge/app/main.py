import json
import time
import uuid

from fastapi import FastAPI, HTTPException

from app.config import Settings
from app.models import ChatCompletionRequest
from app.runner import CodexCliRunner


def create_app(runner=None) -> FastAPI:
    settings = Settings()
    backend = runner or CodexCliRunner(
        command=settings.codex_command,
        timeout_seconds=settings.timeout_seconds,
        max_concurrency=settings.max_concurrency,
    )
    app = FastAPI(title="Local Codex Bridge", version="0.1.0")

    @app.get("/health")
    async def health():
        return {"status": "ok", "backend": "codex-cli"}

    @app.post("/v1/chat/completions")
    async def chat_completions(request: ChatCompletionRequest):
        try:
            result = await backend.run(
                messages=[message.model_dump() for message in request.messages],
                model=request.model,
                reasoning_effort=request.reasoning_effort,
                response_format=request.response_format.model_dump(),
            )
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"Codex execution failed: {exc}") from exc

        return {
            "id": f"chatcmpl-{uuid.uuid4().hex}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": request.model,
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": json.dumps(result, ensure_ascii=False, separators=(",", ":")),
                },
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        }

    return app


app = create_app()
