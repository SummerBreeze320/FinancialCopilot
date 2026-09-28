from typing import Any
from fastapi import APIRouter, Request, WebSocket
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.pipeline.orchestrator import ClarificationRequiredError

router = APIRouter(prefix="/v1/fund-screening", tags=["commands"])


class CommandRequest(BaseModel):
    query: str
    previousDsl: dict | None = None
    conversationId: str | None = None
    debug: bool = False


@router.post("/commands")
async def command(request: CommandRequest, http_request: Request) -> Any:
    try:
        dsl = await http_request.app.state.orchestrator.run(
            request.query, request.previousDsl, conversation_id=request.conversationId
        )
        return {"dsl": dsl.model_dump(by_alias=True, exclude_none=True), "clarification": None}
    except ClarificationRequiredError as exc:
        return {"dsl": None, "clarification": exc.clarification}
    except ValueError as exc:
        return JSONResponse(
            status_code=400,
            content={"error": {"code": "INVALID_QUERY", "message": str(exc)}, "dsl": None, "clarification": None},
        )
    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={"error": {"code": "COMPILATION_ERROR", "message": str(exc)}, "dsl": None, "clarification": None},
        )


@router.websocket("/ws")
async def websocket_command(websocket: WebSocket) -> None:
    await websocket.accept()
    request = CommandRequest.model_validate(await websocket.receive_json())

    async def emit(event: dict) -> None:
        await websocket.send_json(event)

    try:
        dsl = await websocket.app.state.orchestrator.run(
            request.query, request.previousDsl, emit, conversation_id=request.conversationId
        )
        dumped = dsl.model_dump(by_alias=True, exclude_none=True)
        await websocket.send_json({"type": "finalized", "dsl": dumped})
        await websocket.send_json({"type": "final", "dsl": dumped, "trace": {}, "clarification": None})
    except ClarificationRequiredError as exc:
        await websocket.send_json({
            "type": "clarification",
            "clarification": exc.clarification,
            "dsl": None,
        })
    except Exception as exc:
        await websocket.send_json({
            "type": "error",
            "error": {"code": "COMPILATION_ERROR", "message": str(exc)},
            "dsl": None,
        })
