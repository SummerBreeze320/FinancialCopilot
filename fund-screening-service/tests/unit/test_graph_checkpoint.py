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


@pytest.mark.asyncio
async def test_multiturn_graph_editing_with_checkpoint_recovery() -> None:
    from app.models.candidate import CandidateContract
    from app.pipeline.analysis import AnalysisService, RecallPlan
    from app.graph.nodes import ScreeningGraphNodes
    from app.graph.workflow import build_screening_graph

    class DummyMetricRetriever:
        async def search(self, text: str, top_k: int):
            return [
                CandidateContract("metric", "f_return_3y", "近三年收益", supported_usage=("filter", "sort")),
                CandidateContract("metric", "f_info_fundscale", "基金规模", supported_usage=("filter", "sort")),
            ]

    class EmptyRetriever:
        async def search(self, text: str, top_k: int):
            return []

    analysis = AnalysisService(
        lambda q: RecallPlan(("metric",)),
        DummyMetricRetriever(),
        EmptyRetriever(),
        EmptyRetriever(),
        adjudicator=None,
    )
    nodes = ScreeningGraphNodes(analysis)
    saver = MemorySaver()
    graph = build_screening_graph(nodes, checkpointer=saver)

    cid = "test-conv-editing-123"
    cfg = make_graph_config(cid)

    # Turn 1: 筛选规模大于10亿，按近三年收益降序，取前20只
    res1 = await graph.ainvoke(empty_state("规模大于10亿 按近三年收益降序 取前20只", conversation_id=cid), config=cfg)
    assert res1["final_dsl"] is not None
    assert res1["final_dsl"]["limit"] == 20
    assert res1["final_dsl"]["sort"][0]["direction"] == "DESC"

    # Turn 2: 改取前50只 (无 previous_dsl 传入，完全依赖 checkpoint 恢复)
    res2 = await graph.ainvoke(empty_state("改取前50只", conversation_id=cid), config=cfg)
    assert res2["route"] == "simple" or res2.get("intent") == "EDIT"
    assert res2["final_dsl"] is not None
    assert res2["final_dsl"]["limit"] == 50
    assert res2["final_dsl"]["sort"][0]["direction"] == "DESC"

    # Turn 3: 升序 (继续在 Turn 2 基础上修改，保持 limit 50)
    res3 = await graph.ainvoke(empty_state("升序", conversation_id=cid), config=cfg)
    assert res3["final_dsl"] is not None
    assert res3["final_dsl"]["limit"] == 50
    assert res3["final_dsl"]["sort"][0]["direction"] == "ASC"


class MockRedis:
    def __init__(self):
        self.hashes = {}
        self.keys_dict = {}

    def ping(self):
        return True

    def hset(self, name, key=None, value=None, mapping=None):
        if name not in self.hashes:
            self.hashes[name] = {}
        if mapping:
            self.hashes[name].update(mapping)
        if key is not None:
            self.hashes[name][key] = value

    def hget(self, name, key):
        return self.hashes.get(name, {}).get(key)

    def hgetall(self, name):
        return self.hashes.get(name, {})

    def delete(self, *names):
        for n in names:
            self.hashes.pop(n, None)
            self.keys_dict.pop(n, None)


def test_redis_checkpoint_saver_with_mock_client() -> None:
    from app.graph.checkpoint import RedisCheckpointSaver

    mock_client = MockRedis()
    saver = RedisCheckpointSaver(client=mock_client)
    cfg = {"configurable": {"thread_id": "t1", "checkpoint_ns": ""}}
    checkpoint = {
        "v": 1,
        "id": "cp1",
        "ts": "2026-09-28T00:00:00Z",
        "channel_values": {"x": 100},
        "channel_versions": {"x": "0001"},
        "versions_seen": {},
    }
    saved_cfg = saver.put(cfg, checkpoint, {}, {"x": "0001"})
    assert saved_cfg["configurable"]["checkpoint_id"] == "cp1"

    loaded = saver.get_tuple(saved_cfg)
    assert loaded is not None
    assert loaded.checkpoint["id"] == "cp1"
    assert loaded.checkpoint["channel_values"]["x"] == 100


def test_build_checkpointer_falls_back_when_redis_offline() -> None:
    # Invalid port / non-existent server
    saver = build_checkpointer("redis://127.0.0.1:59999/0")
    assert isinstance(saver, MemorySaver)


