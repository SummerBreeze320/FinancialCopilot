from fastapi.testclient import TestClient

from app.main import create_app


def test_http_returns_final_dsl_for_supported_query() -> None:
    response = TestClient(create_app()).post("/v1/fund-screening/commands", json={"query": "成立年限大于10年"})
    assert response.status_code == 200
    assert response.json()["dsl"]["version"] == "fund-screening/v1"
