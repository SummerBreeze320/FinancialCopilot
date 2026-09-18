from typing import Any
from pydantic import BaseModel, Field
from memory_service.domain.enums import MemoryType
from memory_service.domain.memory import MemoryVersion
from memory_service.reader.exact_retriever import ExactRetriever
from memory_service.reader.semantic_retriever import SemanticRetriever
from memory_service.reader.applicability_filter import ApplicabilityFilter
from memory_service.reader.context_assembler import ContextAssembler, MemoryBundle


class RecallRequest(BaseModel):
    user_id: str = Field(description="目标用户ID")
    query_text: str | None = Field(default=None, description="当前提问或任务文本")
    query_embedding: list[float] | None = Field(default=None, description="查询向量")
    task_type: str | None = Field(default=None, description="任务类型标签 (如 portfolio_rebalance)")
    fact_keys: list[str] | None = Field(default=None, description="指定需要精确获取的事实槽位")
    context: dict[str, Any] | None = Field(default=None, description="运行时上下文（如市场环境、可用工具、用户级别）")
    token_budget: int = Field(default=500, ge=50, le=4000, description="注入 Prompt 的 Token 预算上限")
    top_k: int = Field(default=5, ge=1, le=20, description="语义召回最大候选数")


class RecallService:
    """
    在线只读检索总线：
    严格遵守：
    1. 零生成式 LLM 调用（零 Token 成本与不可控延迟）
    2. L0 精确槽位 -> L1 语义检索分层召回
    3. 适用边界硬过滤 (Applicability Filter)
    4. 零 Tokenizer 开销预算贪心拼装 (Context Assembler)
    """

    def __init__(
        self,
        exact_retriever: ExactRetriever,
        semantic_retriever: SemanticRetriever,
        applicability_filter: ApplicabilityFilter | None = None,
        assembler: ContextAssembler | None = None,
    ):
        self.exact_retriever = exact_retriever
        self.semantic_retriever = semantic_retriever
        self.applicability_filter = applicability_filter or ApplicabilityFilter()
        self.assembler = assembler or ContextAssembler()

    async def recall(self, req: RecallRequest) -> MemoryBundle:
        candidates: list[MemoryVersion] = []

        # 1. L0 精确匹配事实与偏好槽位（高优先级）
        exact_pairs = await self.exact_retriever.retrieve(
            user_id=req.user_id,
            fact_keys=req.fact_keys,
        )
        for _, ver in exact_pairs:
            candidates.append(ver)

        # 2. L1 语义向量检索与同任务过程性经验召回
        semantic_pairs = await self.semantic_retriever.retrieve(
            user_id=req.user_id,
            query_embedding=req.query_embedding,
            top_k=req.top_k,
            task_type=req.task_type,
        )
        for ver, _ in semantic_pairs:
            candidates.append(ver)

        # 3. 适用性硬过滤：剔除与当前运行时 context 冲突的经验
        applicable_versions = self.applicability_filter.filter(
            candidates=candidates,
            context=req.context,
        )

        # 4. 预编译 Token 预算拼装
        bundle = self.assembler.assemble(
            versions=applicable_versions,
            token_budget=req.token_budget,
            use_compact=True,
        )

        return bundle
