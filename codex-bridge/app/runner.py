import asyncio
import json
import tempfile
from pathlib import Path


class CodexCliRunner:
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
