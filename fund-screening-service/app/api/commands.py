from typing import Any
from fastapi import APIRouter, Request, WebSocket
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.pipeline.orchestrator import ClarificationRequiredError
from app.graph.checkpoint import make_graph_config
from app.graph.state import empty_state

router = APIRouter(prefix="/v1/fund-screening", tags=["commands"])


class CommandRequest(BaseModel):
    query: str
    previousDsl: dict | None = None
    conversationId: str | None = None
    debug: bool = False


@router.post("/commands")
async def command(request: CommandRequest, http_request: Request) -> Any:
    orchestrator = getattr(http_request.app.state, "orchestrator", None)
    try:
        if hasattr(orchestrator, "run_with_trace"):
            res = await orchestrator.run_with_trace(
                request.query, request.previousDsl, conversation_id=request.conversationId
            )
            return res
        dsl = await orchestrator.run(
            request.query, request.previousDsl, conversation_id=request.conversationId
        )
        return {"dsl": dsl.model_dump(by_alias=True, exclude_none=True), "clarification": None, "route": "simple"}
    except ClarificationRequiredError as exc:
        return {"dsl": None, "clarification": exc.clarification, "route": "clarify"}
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


class RagSearchRequest(BaseModel):
    query: str
    domain: str = "all"
    topK: int = 10


class RagTraceRequest(BaseModel):
    """真实流程 RAG 追踪请求。"""

    query: str


@router.post("/rag-search")
async def rag_search(request: RagSearchRequest, http_request: Request) -> Any:
    compiler = getattr(http_request.app.state, "compiler", None)
    if compiler is None:
        orchestrator = getattr(http_request.app.state, "orchestrator", None)
        compiler = getattr(orchestrator, "_compiler", None) if orchestrator else None
    metric_ret = getattr(compiler, "metric_retriever", None)
    scope_ret = getattr(compiler, "scope_retriever", None)

    metrics_res = []
    scopes_res = []
    if metric_ret and request.domain in ("metric", "all"):
        hits = await metric_ret.search(request.query, request.topK)
        metrics_res = [
            {
                "key": m.key,
                "name": m.name,
                "score": round(m.score, 4),
                "aliases": list(m.aliases),
                "domain": m.domain,
            }
            for m in hits
        ]
    if scope_ret and request.domain in ("scope", "all"):
        hits = await scope_ret.search(request.query, request.topK)
        scopes_res = [
            {
                "key": s.key,
                "name": s.name,
                "score": round(s.score, 4),
                "aliases": list(s.aliases),
                "domain": s.domain,
            }
            for s in hits
        ]

    return {
        "query": request.query,
        "metrics": metrics_res,
        "scopes": scopes_res,
    }


@router.post("/rag-trace")
async def rag_trace(request: RagTraceRequest, http_request: Request) -> Any:
    """执行真实工作流并返回按语义 mention 分发后的候选召回结果。"""
    graph = getattr(http_request.app.state, "graph", None)
    if graph is None:
        return JSONResponse(
            status_code=503,
            content={"error": {"code": "GRAPH_UNAVAILABLE", "message": "LangGraph 工作流不可用"}},
        )

    try:
        result = await graph.ainvoke(empty_state(request.query), config=make_graph_config())
    except Exception as exc:
        return JSONResponse(
            status_code=500,
            content={"error": {"code": "RAG_TRACE_FAILED", "message": str(exc)}},
        )

    by_condition = result.get("by_condition") or {}
    conditions = [
        {
            "conditionId": condition_id,
            "mention": info.get("mention", ""),
            "domain": info.get("domain", ""),
            "candidates": info.get("candidates", []),
        }
        for condition_id, info in by_condition.items()
    ]
    return {
        "query": request.query,
        "route": result.get("route"),
        "semanticPlan": result.get("semantic_plan"),
        "conditions": conditions,
        "trace": result.get("trace", []),
    }
