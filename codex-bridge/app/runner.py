import asyncio
import json
import tempfile
from pathlib import Path
from typing import Any


class CodexCliRunner:
    backend_name = "codex-cli"

    def __init__(self, command: str = "codex", timeout_seconds: float = 180, max_concurrency: int = 1) -> None:
        self._command = command
        self._timeout_seconds = timeout_seconds
        self._semaphore = asyncio.Semaphore(max_concurrency)

    async def run(self, *, messages, model: str, reasoning_effort: str, response_format) -> Any:
        is_json = response_format is not None and response_format.get("type") in ("json_object", "json_schema")
        prompt = self._build_prompt(messages, is_json=is_json)
        async with self._semaphore:
            return await self._execute(prompt, model, reasoning_effort, is_json=is_json)

    async def _execute(self, prompt: str, model: str, reasoning_effort: str, is_json: bool = True) -> Any:
        import shutil
        cmd_path = shutil.which(self._command) or self._command
        codex_model = model if model.startswith(("gpt", "o1", "o3", "codex")) else "gpt-5.6-terra"

        with tempfile.TemporaryDirectory(prefix="codex-bridge-") as temp_dir:
            root = Path(temp_dir)
            output_path = root / ("response.json" if is_json else "response.txt")

            process = await asyncio.create_subprocess_exec(
                cmd_path,
                "exec",
                "-",
                "--model",
                codex_model,
                "--config",
                f'model_reasoning_effort="{reasoning_effort}"',
                "--sandbox",
                "read-only",
                "--ephemeral",
                "--skip-git-repo-check",
                "--output-last-message",
                str(output_path),
                "--color",
                "never",
                "--cd",
                str(root),
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                stdout, stderr = await asyncio.wait_for(
                    process.communicate(prompt.encode("utf-8")),
                    timeout=self._timeout_seconds,
                )
            except TimeoutError:
                process.kill()
                await process.wait()
                raise RuntimeError(f"timed out after {self._timeout_seconds:g}s") from None

            if process.returncode != 0:
                detail = stderr.decode("utf-8", errors="replace").strip()
                raise RuntimeError(detail[-1000:] or f"exit code {process.returncode}")
            if not output_path.exists():
                detail = stdout.decode("utf-8", errors="replace").strip()
                if not is_json and detail:
                    return detail
                raise RuntimeError(f"missing Codex output: {detail[-500:]}")

            raw = output_path.read_text(encoding="utf-8")
            if not is_json:
                return raw.strip()

            try:
                result = json.loads(raw)
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Codex returned invalid JSON: {exc}") from exc
            if not isinstance(result, dict):
                raise RuntimeError("Codex response must be a JSON object")
            return result

    @staticmethod
    def _build_prompt(messages, is_json: bool = True) -> str:
        parts = []
        if is_json:
            parts.append(
                "Act as a JSON-only language-model backend. Do not use tools. "
                "Follow the messages below and return only one JSON object."
            )
        else:
            parts.append("Act as a helpful language-model backend. Do not use tools.")
        for message in messages:
            role = message["role"] if isinstance(message, dict) else message.role
            content = message["content"] if isinstance(message, dict) else message.content
            parts.append(f"<{role}>\n{content}\n</{role}>")
        return "\n\n".join(parts)


class AgyCliRunner:
    backend_name = "agy-cli"
    _agy_eligible: bool | None = None

    def __init__(self, command: str = "agy", timeout_seconds: float = 180, max_concurrency: int = 1) -> None:
        self._command = command
        self._timeout_seconds = timeout_seconds
        self._semaphore = asyncio.Semaphore(max_concurrency)

    async def run(self, *, messages, model: str, reasoning_effort: str, response_format) -> Any:
        schema = self._extract_schema(response_format)
        prompt = self._build_prompt(messages, is_structured=schema is not None)
        if AgyCliRunner._agy_eligible is False:
            codex_runner = CodexCliRunner()
            return await codex_runner._execute(prompt, "gpt-5.6-terra", reasoning_effort, is_json=schema is not None)
        async with self._semaphore:
            return await self._execute(prompt, model, reasoning_effort, schema)

    async def _execute(self, prompt: str, model: str, reasoning_effort: str, schema: dict | None) -> Any:
        with tempfile.TemporaryDirectory(prefix="agy-bridge-") as temp_dir:
            root = Path(temp_dir)
            cmd = [
                self._command,
                "--sandbox",
                "--disable-slash-commands",
                "--model",
                model,
                "--effort",
                reasoning_effort,
                "--print-timeout",
                f"{self._timeout_seconds:g}s",
                "-p",
                prompt,
            ]
            if schema is not None:
                schema_path = root / "response-schema.json"
                schema_path.write_text(json.dumps(schema, ensure_ascii=False), encoding="utf-8")
                cmd.extend([
                    "--output-format",
                    "json",
                    "--json-schema",
                    str(schema_path),
                ])
            else:
                cmd.extend([
                    "--output-format",
                    "text",
                ])

            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                cwd=str(root),
            )
            try:
                stdout, stderr = await asyncio.wait_for(
                    process.communicate(),
                    timeout=self._timeout_seconds + 5,
                )
            except TimeoutError:
                process.kill()
                await process.wait()
                raise RuntimeError(f"timed out after {self._timeout_seconds:g}s") from None

            if process.returncode != 0:
                detail = stderr.decode("utf-8", errors="replace").strip()
                if "Eligibility check failed" in detail or "not currently available in your location" in detail:
                    import logging
                    logging.warning("Agy location restricted, setting AgyCliRunner._agy_eligible = False and falling back to Codex runner...")
                    AgyCliRunner._agy_eligible = False
                    codex_runner = CodexCliRunner()
                    return await codex_runner._execute(prompt, "gpt-5.6-terra", reasoning_effort, is_json=schema is not None)
                raise RuntimeError(detail[-1000:] or f"Agy exit code {process.returncode}")

            if schema is None:
                return stdout.decode("utf-8").strip()

            try:
                envelope = json.loads(stdout.decode("utf-8"))
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Agy returned invalid JSON: {exc}") from exc

            if envelope.get("status") != "SUCCESS":
                detail = str(envelope.get("response") or envelope.get("error") or "unknown failure").strip()
                raise RuntimeError(f"Agy failed: {detail}")
            result = envelope.get("structured_output")
            if not isinstance(result, dict):
                raise RuntimeError("Agy response is missing structured_output")
            return result

    @staticmethod
    def _extract_schema(response_format) -> dict | None:
        if not response_format:
            return None
        rf_type = response_format.get("type")
        if rf_type == "json_schema":
            wrapper = response_format.get("json_schema") or {}
            schema = wrapper.get("schema")
            if isinstance(schema, dict):
                return schema
            return {"type": "object", "additionalProperties": True}
        if rf_type == "json_object":
            return {"type": "object", "additionalProperties": True}
        return None

    @staticmethod
    def _build_prompt(messages, is_structured: bool = True) -> str:
        parts = []
        if is_structured:
            parts.append(
                "Act as a language-model backend. Do not use tools. Follow the messages below; "
                "structured output is enforced externally by the supplied JSON Schema. "
                "Populate the schema fields directly. Do not serialize JSON inside a string field."
            )
        else:
            parts.append("Act as a helpful language-model backend. Do not use tools. Follow the messages below.")
        for message in messages:
            role = message["role"] if isinstance(message, dict) else message.role
            content = message["content"] if isinstance(message, dict) else message.content
            parts.append(f"<{role}>\n{content}\n</{role}>")
        return "\n\n".join(parts)
