import pytest
import os


@pytest.mark.asyncio
async def test_user_cannot_read_another_users_session(client, session_store):
    await session_store.add_message("u1", "c1", "user", "private")
    response = await client.get("/v1/users/u2/sessions/c1/context")
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_owner_can_read_own_session(client, session_store):
    await session_store.add_message("u1", "c1", "user", "private")
    response = await client.get("/v1/users/u1/sessions/c1/context")
    assert response.status_code == 200
    data = response.json()
    assert len(data["messages"]) >= 1
    assert data["messages"][-1]["content"] == "private"


@pytest.mark.asyncio
async def test_service_token_rejected_when_invalid(client, monkeypatch):
    monkeypatch.setenv("MEMORY_SERVICE_TOKEN", "valid-secret")
    response = await client.get(
        "/v1/users/u1/sessions/c1/context",
        headers={"X-Copilot-Service-Token": "wrong-token"},
    )
    assert response.status_code == 401
