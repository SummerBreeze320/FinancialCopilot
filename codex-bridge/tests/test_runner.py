import json
from pathlib import Path

import pytest

from app.runner import AgyCliRunner, CodexCliRunner


@pytest.mark.asyncio
async def test_json_object_mode_does_not_send_an_unknown_output_schema(monkeypatch):
    captured = {}

    class Process:
        returncode = 0

        async def communicate(self, input_bytes=None):
            output_index = captured["args"].index("--output-last-message") + 1
            Path(captured["args"][output_index]).write_text('{"ok":true}', encoding="utf-8")
            return b"", b""

    async def create_process(*args, **kwargs):
        captured["args"] = list(args)
        return Process()

    monkeypatch.setattr("app.runner.asyncio.create_subprocess_exec", create_process)
    result = await CodexCliRunner().run(
        messages=[{"role": "user", "content": "return JSON"}],
        model="gpt-5.6-terra",
        reasoning_effort="low",
        response_format={"type": "json_object"},
    )

    assert result == {"ok": True}
    assert "--output-schema" not in captured["args"]


@pytest.mark.asyncio
async def test_agy_runner_passes_openai_json_schema_and_reads_structured_output(monkeypatch):
    captured = {}

    class Process:
        returncode = 0

        async def communicate(self, input_bytes=None):
            schema_index = captured["args"].index("--json-schema") + 1
            schema_path = Path(captured["args"][schema_index])
            captured["schema"] = schema_path.read_text(encoding="utf-8")
            return b'{"status":"SUCCESS","structured_output":{"conditions":[]}}', b""

    async def create_process(*args, **kwargs):
        captured["args"] = list(args)
        return Process()

    monkeypatch.setattr("app.runner.asyncio.create_subprocess_exec", create_process)
    result = await AgyCliRunner().run(
        messages=[{"role": "user", "content": "analyze"}],
        model="gemini-3.8-flash-low",
        reasoning_effort="low",
        response_format={
            "type": "json_schema",
            "json_schema": {
                "name": "RecallPlan",
                "strict": True,
                "schema": {
                    "type": "object",
                    "properties": {"conditions": {"type": "array"}},
                    "required": ["conditions"],
                },
            },
        },
    )

    assert result == {"conditions": []}
    assert captured["args"][0] == "agy"
    assert captured["args"][captured["args"].index("--model") + 1] == "gemini-3.8-flash-low"
    assert captured["args"][captured["args"].index("--effort") + 1] == "low"
    assert "--sandbox" in captured["args"]
    assert "--disable-slash-commands" in captured["args"]
    assert "--dangerously-skip-permissions" not in captured["args"]
    assert json.loads(captured["schema"])["required"] == ["conditions"]
    prompt = captured["args"][captured["args"].index("-p") + 1]
    assert "return only one JSON object" not in prompt
    assert "structured output is enforced externally" in prompt
    assert "Do not serialize JSON inside a string field" in prompt


@pytest.mark.asyncio
async def test_agy_runner_rejects_unsuccessful_or_unstructured_response(monkeypatch):
    class Process:
        returncode = 0

        async def communicate(self, input_bytes=None):
            return b'{"status":"FAILED","response":"backend unavailable"}', b""

    async def create_process(*args, **kwargs):
        return Process()

    monkeypatch.setattr("app.runner.asyncio.create_subprocess_exec", create_process)

    with pytest.raises(RuntimeError, match="Agy failed: backend unavailable"):
        await AgyCliRunner().run(
            messages=[{"role": "user", "content": "analyze"}],
            model="gemini-3.8-flash-low",
            reasoning_effort="low",
            response_format={"type": "json_object"},
        )
