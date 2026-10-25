"""Memory Service HTTP API 接口集成测试"""

import pytest
from datetime import datetime, timezone


@pytest.mark.asyncio
async def test_memory_api_full_workflow(client):
    # 1. 显式写入记忆
    payload = {
        "user_id": "api_user_1",
        "content": "请记住：我只做稳健理财，不要推荐任何微盘股！",
        "importance": 0.85,
    }
    resp = await client.post("/v1/memory/explicit", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["category"] == "USER_FEEDBACK"
    assert "微盘股" in data["fact"]

    # 2. 多因子检索
    search_payload = {
        "user_id": "api_user_1",
        "query": "微盘股",
        "w_semantic": 0.5,
        "w_recency": 0.3,
        "w_importance": 0.2,
    }
    resp = await client.post("/v1/memory/search", json=search_payload)
    assert resp.status_code == 200
    s_data = resp.json()
    assert s_data["total"] >= 1
    assert s_data["results"][0]["score"] > 0

    # 3. 整理去重
    con_payload = {"user_id": "api_user_1"}
    resp = await client.post("/v1/memory/consolidate", json=con_payload)
    assert resp.status_code == 200
    c_data = resp.json()
    assert c_data["processed_count"] >= 1

    # 4. 修剪
    prune_payload = {
        "user_id": "api_user_1",
        "max_idle_days": 90,
        "min_importance": 0.2,
        "prune_invalidated": True,
    }
    resp = await client.post("/v1/memory/prune", json=prune_payload)
    assert resp.status_code == 200

    # 5. 条件遗忘
    forget_payload = {
        "user_id": "api_user_1",
        "query": "微盘股",
    }
    resp = await client.post("/v1/memory/forget", json=forget_payload)
    assert resp.status_code == 200
    f_data = resp.json()
    assert f_data["long_term_cleared"] >= 1
