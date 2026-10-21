from fastapi.testclient import TestClient
from langgraph.graph.state import CompiledStateGraph

from app.graph.compiler import LangGraphCommandCompiler
from app.graph.workflow import build_screening_graph
from app.main import create_app


def test_app_holds_compiled_graph_and_routes_identically() -> None:
    app = create_app()
    assert hasattr(app.state, "graph")
    assert isinstance(app.state.graph, CompiledStateGraph)

    client = TestClient(app)
    query = "筛选最大回撤低于15%、规模大于10亿的主动权益基金，按近3年收益降序取前20只"

    # HTTP 请求
    http_resp = client.post("/v1/fund-screening/commands", json={"query": query})
    assert http_resp.status_code == 200
    data = http_resp.json()
    assert "dsl" in data
    http_dsl = data["dsl"]

    # WebSocket 请求
    with client.websocket_connect("/v1/fund-screening/ws") as ws:
        ws.send_json({"query": query})
        events = []
        for _ in range(25):
            try:
                ev = ws.receive_json()
                events.append(ev)
                if ev.get("type") in ("finalized", "final"):
                    break
            except Exception:
                break

    stages = [e["stage"] for e in events if e.get("type") in ("stage", "stage_completed")]
    assert len(stages) >= 3
    final_event = events[-1]
    assert final_event["dsl"] == http_dsl


def test_http_and_ws_both_execute_same_langgraph_for_simple_edit() -> None:
    client = TestClient(create_app())
    first = client.post("/v1/fund-screening/commands", json={"query": "筛选最大回撤低于15%、规模大于10亿的主动权益基金，按近3年收益降序取前20只"}).json()["dsl"]

    # HTTP simple edit
    http_resp = client.post("/v1/fund-screening/commands", json={"query": "改成前50只", "previousDsl": first})
    assert http_resp.status_code == 200
    http_dsl = http_resp.json()["dsl"]

    # WebSocket simple edit
    with client.websocket_connect("/v1/fund-screening/ws") as ws:
        ws.send_json({"query": "改成前50只", "previousDsl": first})
        events = []
        for _ in range(25):
            try:
                ev = ws.receive_json()
                events.append(ev)
                if ev.get("type") in ("finalized", "final"):
                    break
            except Exception:
                break
    ws_dsl = events[-1]["dsl"]

    assert http_dsl["limit"] == 50
    assert ws_dsl["limit"] == 50
    assert http_dsl == ws_dsl
    for h_child, f_child in zip(http_dsl["children"], first["children"]):
        assert h_child["sector"] == f_child["sector"]
        assert h_child["metric"] == f_child["metric"]

