"""FastMCP 服务端与 JSON-RPC 兼容接口单元测试"""
import pytest
from starlette.testclient import TestClient
from app.server import app


@pytest.fixture
def client():
    return TestClient(app, base_url="http://127.0.0.1:8085")


def test_health_check(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "UP"
    assert data["tools_count"] >= 5


def test_tools_list_json_rpc(client):
    payload = {
        "jsonrpc": "2.0",
        "id": "req-101",
        "method": "tools/list"
    }
    resp = client.post("/messages", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["jsonrpc"] == "2.0"
    assert data["id"] == "req-101"
    assert "tools" in data["result"]
    tools = data["result"]["tools"]
    tool_names = [t["name"] for t in tools]
    assert "recognize_entities" in tool_names
    assert "search_memory" in tool_names
    assert "fund_metrics" in tool_names
    assert "stock_metrics" in tool_names
    assert "get_market_indices" in tool_names


def test_tool_call_fund_metrics(client):
    payload = {
        "jsonrpc": "2.0",
        "id": "req-102",
        "method": "tools/call",
        "params": {
            "name": "fund_metrics",
            "arguments": {
                "fund_code": "005827"
            }
        }
    }
    resp = client.post("/messages", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["id"] == "req-102"
    assert not data["result"]["isError"]
    content = data["result"]["content"]
    assert len(content) > 0
    assert content[0]["type"] == "text"
    assert "005827" in content[0]["text"]
    assert "sharpe_ratio_3y" in content[0]["text"]


def test_tool_call_macro_news(client):
    payload = {
        "jsonrpc": "2.0",
        "id": "req-103",
        "method": "tools/call",
        "params": {
            "name": "macro_news_realtime",
            "arguments": {
                "keyword": "流动性",
                "limit": 3
            }
        }
    }
    resp = client.post("/messages", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert not data["result"]["isError"]
    assert "流动性" in data["result"]["content"][0]["text"]


def test_ping_method(client):
    payload = {
        "jsonrpc": "2.0",
        "id": "req-104",
        "method": "ping"
    }
    resp = client.post("/messages", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["id"] == "req-104"
    assert data["result"] == {}


def test_unknown_method(client):
    payload = {
        "jsonrpc": "2.0",
        "id": "req-105",
        "method": "unknown/method"
    }
    resp = client.post("/messages", json=payload)
    assert resp.status_code == 400
    data = resp.json()
    assert data["error"]["code"] == -32601
