from fastapi.testclient import TestClient

from app.main import create_app


def test_websocket_emits_five_stages_then_finalized() -> None:
    with TestClient(create_app()).websocket_connect("/v1/fund-screening/ws") as socket:
        socket.send_json({"query": "成立年限大于10年"})
        events = [socket.receive_json() for _ in range(11)]
    assert [item["stage"] for item in events if item["type"] == "stage_completed"] == ["query_preprocess", "query_analysis_and_recall", "command_build", "validator_and_repair", "command_finalize"]
    assert events[-1]["type"] == "finalized"
