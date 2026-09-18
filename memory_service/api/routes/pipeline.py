from typing import Any
from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy.ext.asyncio import AsyncSession
from memory_service.infrastructure.mysql.session import get_db_session
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.publication.memory_publisher import MemoryPublisher
from memory_service.engine.pipeline import OfflineMemoryPipeline

router = APIRouter()


class ProcessSessionRequest(BaseModel):
    session_id: str = Field(description="会话ID")
    user_id: str = Field(description="用户ID")
    session_data: dict[str, Any] = Field(description="已准备好的会话/工具执行记录")


@router.post("/process-session")
async def process_session(
    request: ProcessSessionRequest,
    session: AsyncSession = Depends(get_db_session),
) -> dict[str, Any]:
    """
    离线记忆加工入口端点：
    接收已准备好的会话数据，提取事实与经验，验证后发布
    """
    repo = MemoryRepository(session)
    publisher = MemoryPublisher(repo)
    pipeline = OfflineMemoryPipeline(memory_repo=repo, publisher=publisher)

    result = await pipeline.process_session(
        session_id=request.session_id,
        user_id=request.user_id,
        session_data=request.session_data,
    )
    await session.commit()
    return result
