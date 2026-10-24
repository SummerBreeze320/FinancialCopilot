from fastapi.testclient import TestClient

from app.main import create_app


def test_websocket_emits_five_stages_then_finalized() -> None:
    with TestClient(create_app()).websocket_connect("/v1/fund-screening/ws") as socket:
        socket.send_json({"query": "成立年限大于10年"})
        events = [socket.receive_json() for _ in range(11)]
    assert [item["stage"] for item in events if item["type"] == "stage_completed"] == ["query_preprocess", "query_analysis_and_recall", "command_build", "validator_and_repair", "command_finalize"]
    assert events[-1]["type"] == "finalized"


def test_websocket_simple_edit_preserves_prior_conditions() -> None:
    client = TestClient(create_app())
    first = client.post("/v1/fund-screening/commands", json={"query": "筛选最大回撤低于15%、规模大于10亿的主动权益基金，按近3年收益降序取前20只"}).json()["dsl"]

    with client.websocket_connect("/v1/fund-screening/ws") as socket:
        socket.send_json({"query": "改成前50只", "previousDsl": first})
        events = []
        for _ in range(20):
            try:
                ev = socket.receive_json()
                events.append(ev)
                if ev.get("type") in ("finalized", "final"):
                    break
            except Exception:
                break
    final_event = events[-1]
    assert final_event["dsl"]["limit"] == 50
    for a_child, f_child in zip(final_event["dsl"]["children"], first["children"]):
        assert a_child["sector"] == f_child["sector"]
        assert a_child["metric"] == f_child["metric"]


def test_websocket_multiturn_editing_via_conversation_id() -> None:
    client = TestClient(create_app())
    cid = "ws-session-checkpoint-1"

    # Turn 1
    with client.websocket_connect("/v1/fund-screening/ws") as socket:
        socket.send_json({"query": "成立年限大于10年 取前20只", "conversationId": cid})
        events1 = []
        for _ in range(20):
            try:
                ev = socket.receive_json()
                events1.append(ev)
                if ev.get("type") in ("finalized", "final"):
                    break
            except Exception:
                break
    assert events1[-1]["dsl"]["limit"] == 20

    # Turn 2: 改取前60只 (No previousDsl passed; state restored from checkpoint)
    with client.websocket_connect("/v1/fund-screening/ws") as socket:
        socket.send_json({"query": "改取前60只", "conversationId": cid})
        events2 = []
        for _ in range(20):
            try:
                ev = socket.receive_json()
                events2.append(ev)
                if ev.get("type") in ("finalized", "final"):
                    break
            except Exception:
                break
    assert events2[-1]["dsl"]["limit"] == 60


