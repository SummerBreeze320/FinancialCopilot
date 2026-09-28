from fastapi.testclient import TestClient

from app.main import create_app


def test_health_returns_service_and_dependency_states() -> None:
    client = TestClient(create_app())

    response = client.get("/v1/fund-screening/health")

    assert response.status_code == 200
    assert response.json()["service"] == "fund-screening-service"
    assert set(response.json()["dependencies"]) == {"catalog", "milvus", "redis", "llm"}
