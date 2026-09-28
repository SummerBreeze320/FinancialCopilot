from fastapi.testclient import TestClient

from app.main import create_app


class RecordingRunner:
    def __init__(self, result=None, error=None):
        self.result = result or {"conditions": []}
        self.error = error
        self.calls = []

    async def run(self, *, messages, model, reasoning_effort, response_format):
        self.calls.append({
            "messages": messages,
            "model": model,
            "reasoning_effort": reasoning_effort,
            "response_format": response_format,
        })
        if self.error:
            raise self.error
        return self.result


def test_chat_completions_wraps_codex_json_as_openai_response():
    runner = RecordingRunner({"conditions": [{"domain": "metric"}]})
    client = TestClient(create_app(runner))

    response = client.post("/v1/chat/completions", json={
        "model": "gpt-5.6-terra",
        "reasoning_effort": "low",
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "Only return JSON."},
            {"role": "user", "content": "筛选近三年收益率高的基金"},
        ],
    })

    assert response.status_code == 200
    body = response.json()
    assert body["object"] == "chat.completion"
    assert body["model"] == "gpt-5.6-terra"
    assert body["choices"][0]["message"]["role"] == "assistant"
    assert body["choices"][0]["message"]["content"] == '{"conditions":[{"domain":"metric"}]}'
    assert runner.calls == [{
        "messages": [
            {"role": "system", "content": "Only return JSON."},
            {"role": "user", "content": "筛选近三年收益率高的基金"},
        ],
        "model": "gpt-5.6-terra",
        "reasoning_effort": "low",
        "response_format": {"type": "json_object"},
    }]


def test_chat_completions_maps_runner_failure_to_bad_gateway():
    client = TestClient(create_app(RecordingRunner(error=RuntimeError("codex failed"))))

    response = client.post("/v1/chat/completions", json={
        "model": "gpt-5.6-terra",
        "messages": [{"role": "user", "content": "hello"}],
    })

    assert response.status_code == 502
    assert response.json() == {"detail": "Codex execution failed: codex failed"}


def test_health_reports_ready():
    response = TestClient(create_app(RecordingRunner())).get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "backend": "codex-cli"}


def test_chat_completions_defaults_to_locally_available_codex_model():
    runner = RecordingRunner({"ok": True})
    response = TestClient(create_app(runner)).post("/v1/chat/completions", json={
        "messages": [{"role": "user", "content": "hello"}],
    })

    assert response.status_code == 200
    assert response.json()["model"] == "gpt-5.6-terra"
    assert runner.calls[0]["model"] == "gpt-5.6-terra"
