from fastapi import APIRouter, WebSocket
from pydantic import BaseModel

from app.pipeline.orchestrator import ScreeningOrchestrator

router = APIRouter(prefix="/v1/fund-screening", tags=["commands"])
orchestrator = ScreeningOrchestrator()


class CommandRequest(BaseModel):
    query: str
    previousDsl: dict | None = None
    conversationId: str | None = None
    debug: bool = False


@router.post("/commands")
async def command(request: CommandRequest) -> dict:
    dsl = await orchestrator.run(request.query, request.previousDsl)
    return {"dsl": dsl.model_dump(by_alias=True, exclude_none=True)}


@router.websocket("/ws")
async def websocket_command(websocket: WebSocket) -> None:
    await websocket.accept()
    request = CommandRequest.model_validate(await websocket.receive_json())

    async def emit(event: dict) -> None:
        await websocket.send_json(event)
    dsl = await orchestrator.run(request.query, request.previousDsl, emit)
    await websocket.send_json({"type": "finalized", "dsl": dsl.model_dump(by_alias=True, exclude_none=True)})
