from pathlib import Path

import pytest

from app.runner import CodexCliRunner


@pytest.mark.asyncio
async def test_json_object_mode_does_not_send_an_unknown_output_schema(monkeypatch):
    captured = {}

    class Process:
        returncode = 0

        async def communicate(self, input_bytes):
            captured["input"] = input_bytes.decode("utf-8")
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
