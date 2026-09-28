from __future__ import annotations

import uuid
from typing import Any
from langgraph.checkpoint.base import BaseCheckpointSaver
from langgraph.checkpoint.memory import MemorySaver


def build_checkpointer(redis_url: str | None = None) -> BaseCheckpointSaver:
    if redis_url:
        try:
            from langgraph_checkpoint_redis import RedisSaver  # type: ignore[import-not-found]
            return RedisSaver.from_conn_string(redis_url)
        except Exception:
            return MemorySaver()
    return MemorySaver()


def make_graph_config(conversation_id: str | None = None) -> dict[str, Any]:
    thread_id = conversation_id if conversation_id else str(uuid.uuid4())
    return {"configurable": {"thread_id": thread_id}}
