import pytest
from app.engine.short_term import short_term_key, session_owner_key


@pytest.mark.asyncio
async def test_user_search_passes_private_namespace(memory_engine):
    await memory_engine.search("u1", "USER", "低回撤", 5)
    assert memory_engine.last_namespace == "user:u1"


@pytest.mark.asyncio
async def test_global_market_search_passes_global_namespace(memory_engine):
    await memory_engine.search(None, "GLOBAL_MARKET", "宏观", 5)
    assert memory_engine.last_namespace == "global:market"


@pytest.mark.asyncio
async def test_conversation_search_passes_conversation_namespace(memory_engine):
    await memory_engine.search("u1", "CONVERSATION", "某基金", 5, conversation_id="c1")
    assert memory_engine.last_namespace == "user:u1:conversation:c1"


def test_short_term_key_formatting():
    assert short_term_key("u1", "c1") == "memory:shortterm:user:u1:conversation:c1"
    assert session_owner_key("c1") == "memory:owner:conversation:c1"
