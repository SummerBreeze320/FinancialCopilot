import asyncio
from types import SimpleNamespace

import pytest

from app import main


@pytest.mark.asyncio
async def test_embedding_client_bypasses_environment_proxy(monkeypatch):
    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        await reader.read(4096)
        body = b'{"object":"list","data":[]}'
        writer.write(
            b"HTTP/1.1 200 OK\r\n"
            + f"Content-Length: {len(body)}\r\n".encode()
            + b"Content-Type: application/json\r\nConnection: close\r\n\r\n"
            + body
        )
        await writer.drain()
        writer.close()
        await writer.wait_closed()

    server = await asyncio.start_server(handle, "127.0.0.1", 0)
    port = server.sockets[0].getsockname()[1]
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    monkeypatch.setenv("NO_PROXY", "")
    settings = SimpleNamespace(
        embedding_base_url=f"http://127.0.0.1:{port}/v1",
        request_timeout_seconds=2.0,
    )

    try:
        client = main.create_embedding_http_client(settings)
        async with client:
            response = await client.get("/models")
        assert response.status_code == 200
    finally:
        server.close()
        await server.wait_closed()
