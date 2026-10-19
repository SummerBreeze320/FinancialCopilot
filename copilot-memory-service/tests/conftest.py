import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport

from app.main import app
from app.engine.short_term import short_term_engine
from app.engine.long_term import long_term_engine


@pytest.fixture
def session_store():
    return short_term_engine


@pytest.fixture
def memory_engine():
    return long_term_engine


@pytest_asyncio.fixture
async def client():
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
