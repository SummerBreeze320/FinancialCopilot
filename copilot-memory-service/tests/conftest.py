import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport

from unittest.mock import AsyncMock, MagicMock
from app.main import app
from app.engine.short_term import short_term_engine
from app.engine.long_term import long_term_engine


@pytest.fixture(autouse=True)
def mock_graphiti_default(monkeypatch):
    mock_g = MagicMock()
    mock_g.add_episode = AsyncMock(return_value=None)
    mock_g.search = AsyncMock(return_value=[])
    mock_g.build_indices_and_constraints = AsyncMock(return_value=None)
    mock_g.close = AsyncMock(return_value=None)
    monkeypatch.setattr("app.engine.short_term.get_graphiti", lambda: mock_g)
    monkeypatch.setattr("app.engine.long_term.get_graphiti", lambda: mock_g)
    monkeypatch.setattr("app.engine.profile_engine.get_graphiti", lambda: mock_g)
    monkeypatch.setattr("app.main.init_graphiti_indices", AsyncMock(return_value=None))
    monkeypatch.setattr("app.main.close_graphiti", AsyncMock(return_value=None))
    return mock_g


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
