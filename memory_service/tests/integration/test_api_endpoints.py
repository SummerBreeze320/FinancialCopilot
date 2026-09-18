import pytest
from httpx import AsyncClient, ASGITransport
from memory_service.api.main import app
from memory_service.infrastructure.mysql.session import get_db_session


@pytest.mark.asyncio
async def test_health_check():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert data["service"] == "memory_service"


@pytest.mark.asyncio
async def test_process_session_and_recall_flow(db_session):
    # 覆盖数据库依赖为内存 SQLite session
    async def override_get_db_session():
        yield db_session

    app.dependency_overrides[get_db_session] = override_get_db_session

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        user_id = "api_test_user_88"
        session_id = "sess_api_001"

        # 1. 发送会话数据进行离线处理
        payload = {
            "session_id": session_id,
            "user_id": user_id,
            "session_data": {
                "messages": [
                    {"role": "user", "content": "我的风险偏好是进取型，每月打算定投3000元，排除医药"},
                ],
                "tool_calls": [],
            },
        }
        res = await client.post("/api/v1/memory/process-session", json=payload)
        assert res.status_code == 200
        proc_data = res.json()
        assert proc_data["facts_published"] >= 1

        # 2. 调用在线记忆检索
        recall_payload = {
            "user_id": user_id,
            "token_budget": 500,
        }
        recall_res = await client.post("/api/v1/memory/recall", json=recall_payload)
        assert recall_res.status_code == 200
        bundle = recall_res.json()

        assert len(bundle["recalled_items"]) >= 1
        assert "risk_tolerance" in bundle["compact_context"]
        assert bundle["total_tokens"] > 0
        assert bundle["total_tokens"] <= 500

    app.dependency_overrides.clear()
