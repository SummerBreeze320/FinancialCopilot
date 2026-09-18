from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession
from memory_service.infrastructure.mysql.session import get_db_session
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.reader.exact_retriever import ExactRetriever
from memory_service.reader.semantic_retriever import SemanticRetriever
from memory_service.reader.recall_service import RecallService, RecallRequest
from memory_service.reader.context_assembler import MemoryBundle

router = APIRouter()


@router.post("/recall", response_model=MemoryBundle)
async def recall_memory(
    request: RecallRequest,
    session: AsyncSession = Depends(get_db_session),
) -> MemoryBundle:
    """
    在线长期记忆检索端点：
    零生成式 LLM 调用，严格遵守 Token 预算与适用性过滤
    """
    repo = MemoryRepository(session)
    exact_retriever = ExactRetriever(repo)
    semantic_retriever = SemanticRetriever(repo)
    service = RecallService(
        exact_retriever=exact_retriever,
        semantic_retriever=semantic_retriever,
    )
    return await service.recall(request)
