import asyncio
import json
import tempfile
from pathlib import Path


class CodexCliRunner:
    backend_name = "codex-cli"

    def __init__(self, command: str = "codex", timeout_seconds: float = 180, max_concurrency: int = 1) -> None:
        self._command = command
        self._timeout_seconds = timeout_seconds
        self._semaphore = asyncio.Semaphore(max_concurrency)

    async def run(self, *, messages, model: str, reasoning_effort: str, response_format) -> dict:
        prompt = self._build_prompt(messages)
        async with self._semaphore:
            return await self._execute(prompt, model, reasoning_effort)

    async def _execute(self, prompt: str, model: str, reasoning_effort: str) -> dict:
        with tempfile.TemporaryDirectory(prefix="codex-bridge-") as temp_dir:
            root = Path(temp_dir)
            output_path = root / "response.json"

            process = await asyncio.create_subprocess_exec(
                self._command,
                "exec",
                "-",
                "--model",
                model,
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
                raise RuntimeError(f"missing Codex output: {detail[-500:]}")

            try:
                result = json.loads(output_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError as exc:
                raise RuntimeError(f"Codex returned invalid JSON: {exc}") from exc
            if not isinstance(result, dict):
                raise RuntimeError("Codex response must be a JSON object")
            return result

    @staticmethod
    def _build_prompt(messages) -> str:
        parts = [
            "Act as a JSON-only language-model backend. Do not use tools. "
            "Follow the messages below and return only one JSON object.",
        ]
        for message in messages:
            role = message["role"] if isinstance(message, dict) else message.role
            content = message["content"] if isinstance(message, dict) else message.content
            parts.append(f"<{role}>\n{content}\n</{role}>")
        return "\n\n".join(parts)


class AgyCliRunner:
    backend_name = "agy-cli"

    def __init__(self, command: str = "agy", timeout_seconds: float = 180, max_concurrency: int = 1) -> None:
        self._command = command
        self._timeout_seconds = timeout_seconds
        self._semaphore = asyncio.Semaphore(max_concurrency)

    async def run(self, *, messages, model: str, reasoning_effort: str, response_format) -> dict:
        prompt = self._build_prompt(messages)
        schema = self._extract_schema(response_format)
        async with self._semaphore:
            return await self._execute(prompt, model, reasoning_effort, schema)

    async def _execute(self, prompt: str, model: str, reasoning_effort: str, schema: dict) -> dict:
        with tempfile.TemporaryDirectory(prefix="agy-bridge-") as temp_dir:
            root = Path(temp_dir)
            schema_path = root / "response-schema.json"
            schema_path.write_text(json.dumps(schema, ensure_ascii=False), encoding="utf-8")

            process = await asyncio.create_subprocess_exec(
                self._command,
                "--sandbox",
                "--disable-slash-commands",
                "--output-format",
                "json",
                "--json-schema",
                str(schema_path),
                "--model",
                model,
                "--effort",
                reasoning_effort,
                "--print-timeout",
                f"{self._timeout_seconds:g}s",
                "-p",
                prompt,
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
                raise RuntimeError(detail[-1000:] or f"Agy exit code {process.returncode}")

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
    def _extract_schema(response_format) -> dict:
        if response_format.get("type") == "json_schema":
            wrapper = response_format.get("json_schema") or {}
            schema = wrapper.get("schema")
            if isinstance(schema, dict):
                return schema
        return {"type": "object", "additionalProperties": True}

    @staticmethod
    def _build_prompt(messages) -> str:
        parts = [
            "Act as a language-model backend. Do not use tools. Follow the messages below; "
            "structured output is enforced externally by the supplied JSON Schema. "
            "Populate the schema fields directly. Do not serialize JSON inside a string field.",
        ]
        for message in messages:
            role = message["role"] if isinstance(message, dict) else message.role
            content = message["content"] if isinstance(message, dict) else message.content
            parts.append(f"<{role}>\n{content}\n</{role}>")
        return "\n\n".join(parts)
