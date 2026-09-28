import pytest
from app.graph.checkpoint import build_checkpointer, make_graph_config
from app.graph.state import empty_state
from langgraph.checkpoint.memory import MemorySaver


def test_build_checkpointer_returns_memory_saver_by_default() -> None:
    saver = build_checkpointer(None)
    assert isinstance(saver, MemorySaver)


def test_make_graph_config_sets_thread_id() -> None:
    config = make_graph_config("session-999")
    assert config["configurable"]["thread_id"] == "session-999"


def test_make_graph_config_generates_id_when_none() -> None:
    config = make_graph_config(None)
    assert "thread_id" in config["configurable"]
    assert len(config["configurable"]["thread_id"]) > 0
