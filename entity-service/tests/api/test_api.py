import jsonschema

from app.models.api import RecognitionResponse


def test_recognize_contract(client):
    response = client.post("/v1/entities/recognize", json={"text": "华夏成长"})
    assert response.status_code == 200
    parsed = RecognitionResponse.model_validate(response.json())
    assert parsed.entities[0].entity.id == "fund_name:000001.OF"
    assert response.headers["x-request-id"] == parsed.request_id


def test_schema_endpoint_publishes_valid_schema(client):
    schema = client.get("/v1/schema/recognition-response").json()
    jsonschema.Draft202012Validator.check_schema(schema)


def test_rebuild_requires_admin_token(client):
    response = client.post("/v1/entities/index/rebuild", json={"force": False})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_rebuild_and_job_status(client):
    response = client.post(
        "/v1/entities/index/rebuild",
        headers={"Authorization": "Bearer test-token"},
        json={"force": False},
    )
    assert response.status_code == 202
    assert response.json()["job_id"] == "job-1"
    status = client.get("/v1/entities/index/jobs/job-1")
    assert status.status_code == 200
    assert status.json()["processed"] == 11


def test_entity_lookup_and_not_found(client):
    assert client.get("/v1/entities/fund_name:000001.OF").status_code == 200
    missing = client.get("/v1/entities/missing:x")
    assert missing.status_code == 404
    assert missing.json()["error"]["code"] == "ENTITY_NOT_FOUND"


def test_health_and_discovery(client):
    health = client.get("/health")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"
    endpoints = client.get("/").json()["endpoints"]
    assert endpoints["recognize"] == "/v1/entities/recognize"
