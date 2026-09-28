from __future__ import annotations

import os
import pytest

from app.config import Settings
from app.graph.state import empty_state
from app.models.dsl import FundScreeningDsl
from app.runtime import build_production_compiler

pytestmark = pytest.mark.skipif(
    os.getenv("RUN_REAL_FUND_SCREENING_TESTS") != "1",
    reason="Requires live external services (LLM, PgVector, Redis). Set RUN_REAL_FUND_SCREENING_TESTS=1 to run.",
)


@pytest.fixture(scope="module")
def real_compiler():
    settings = Settings()
    return build_production_compiler(settings)


@pytest.mark.asyncio
async def test_real_direct_route_smoke(real_compiler) -> None:
    # 纯代码精确查询走 direct 分流 (0 次 LLM)
    query = "001938"
    result = await real_compiler.compile(query)
    assert result.dsl is not None
    assert isinstance(result.dsl, FundScreeningDsl)
    assert result.trace["route"] == "direct"


@pytest.mark.asyncio
async def test_real_simple_route_smoke(real_compiler) -> None:
    # 简单单组条件走 simple 分流 (<= 1 次 LLM)
    query = "规模大于10亿且成立超过5年"
    result = await real_compiler.compile(query)
    assert result.dsl is not None
    assert isinstance(result.dsl, FundScreeningDsl)
    assert result.trace["route"] == "simple"


@pytest.mark.asyncio
async def test_real_full_route_smoke(real_compiler) -> None:
    # 复杂组合逻辑走 full 分流 (严格 2 次 LLM)
    query = "筛选规模大于10亿或者最大回撤小于15%的主动权益基金，按近3年收益降序取前20只"
    result = await real_compiler.compile(query)
    assert result.dsl is not None
    assert isinstance(result.dsl, FundScreeningDsl)
    assert result.trace["route"] == "full"


@pytest.mark.asyncio
async def test_real_multiturn_edit_smoke(real_compiler) -> None:
    # 多轮基于 checkpoint 的编辑
    cid = f"smoke-test-{os.urandom(4).hex()}"
    res1 = await real_compiler.compile("规模大于10亿 取前20只", conversation_id=cid)
    assert res1.dsl.limit == 20

    res2 = await real_compiler.compile("改取前50只", conversation_id=cid)
    assert res2.dsl.limit == 50
