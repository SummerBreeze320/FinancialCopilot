from fastapi import APIRouter, Request, WebSocket
from pydantic import BaseModel

router = APIRouter(prefix="/v1/fund-screening", tags=["commands"])


class CommandRequest(BaseModel):
    query: str
    previousDsl: dict | None = None
    conversationId: str | None = None
    debug: bool = False


@router.post("/commands")
async def command(request: CommandRequest, http_request: Request) -> dict:
    dsl = await http_request.app.state.orchestrator.run(
        request.query, request.previousDsl, conversation_id=request.conversationId
    )
    return {"dsl": dsl.model_dump(by_alias=True, exclude_none=True)}


@router.websocket("/ws")
async def websocket_command(websocket: WebSocket) -> None:
    await websocket.accept()
    request = CommandRequest.model_validate(await websocket.receive_json())

    async def emit(event: dict) -> None:
        await websocket.send_json(event)

    dsl = await websocket.app.state.orchestrator.run(
        request.query, request.previousDsl, emit, conversation_id=request.conversationId
    )
    dumped = dsl.model_dump(by_alias=True, exclude_none=True)
    await websocket.send_json({"type": "finalized", "dsl": dumped})
    await websocket.send_json({"type": "final", "dsl": dumped, "trace": {}, "clarification": None})
