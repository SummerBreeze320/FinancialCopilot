import uuid
from typing import Any
from memory_service.domain.enums import MemoryType, VerificationStatus, MemoryStatus
from memory_service.domain.memory import Memory, MemoryVersion
from memory_service.domain.fact import FactSlot
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.compilation.memory_compiler import MemoryCompiler
from memory_service.engine.publication.memory_publisher import MemoryPublisher
from memory_service.poc.mem0_adapter import Mem0FactAdapter
from memory_service.poc.reme_adapter import ReMeExperienceAdapter


class UnifiedPocPipeline:
    """
    Mem0 (事实) + ReMe (经验) 统一融合 PoC 流水线：
    验证：
    1. Mem0 能否以无状态适配器形式提取事实，而不侵占 MySQL 权威存储。
    2. ReMe 能否以算法适配器形式蒸馏轨迹经验，而不写本地 Markdown。
    3. 两者能否统一汇入 MySQL 不可变版本架构，并输出统一的 MemoryPack。
    """

    def __init__(
        self,
        memory_repo: MemoryRepository,
        publisher: MemoryPublisher,
        mem0_adapter: Mem0FactAdapter | None = None,
        reme_adapter: ReMeExperienceAdapter | None = None,
        compiler: MemoryCompiler | None = None,
    ):
        self.repo = memory_repo
        self.publisher = publisher
        self.mem0 = mem0_adapter or Mem0FactAdapter()
        self.reme = reme_adapter or ReMeExperienceAdapter()
        self.compiler = compiler or MemoryCompiler()

    async def ingest_session(
        self,
        session_id: str,
        user_id: str,
        session_data: dict[str, Any],
    ) -> dict[str, Any]:
        messages = session_data.get("messages", [])
        tool_calls = session_data.get("tool_calls", [])
        task_type = session_data.get("task_type", "general_task")
        errors = session_data.get("errors", [])

        # 1. 使用 Mem0 适配器提取原子事实
        extracted_facts = self.mem0.extract_facts_stateless(messages)
        facts_published = 0

        for fact in extracted_facts:
            # 校验与构建统一 Memory 快照
            mem_id = str(uuid.uuid4())
            content = f"User {fact.predicate} is {fact.value}"
            pack = self.compiler.compile(
                memory_id=mem_id,
                version=1,
                memory_type=MemoryType.FACT,
                content=content,
                data_json={"subject": fact.subject, "predicate": fact.predicate, "value": fact.value},
            )
            version_obj = MemoryVersion(
                memory_id=mem_id,
                version=1,
                content=content,
                data_json=fact.model_dump(),
                verification_status=VerificationStatus.VALIDATED,
                pack=pack,
                fact_detail=fact,
            )
            memory_obj = Memory(id=mem_id, user_id=user_id, memory_type=MemoryType.FACT, status=MemoryStatus.DRAFT)
            await self.repo.create_memory(memory_obj, version_obj)

            slot = FactSlot(
                user_id=user_id,
                fact_key=fact.predicate,
                memory_id=mem_id,
                current_version=1,
                value=fact.value,
            )
            pub_ok = await self.publisher.publish(memory=memory_obj, version=version_obj, fact_key=fact.predicate, slot_value=fact.value)
            if pub_ok:
                facts_published += 1

        # 2. 使用 ReMe 适配器提炼过程性经验
        procedure_published = False
        if tool_calls:
            proc = self.reme.distill_trajectory(
                task_type=task_type,
                trajectory_events=tool_calls,
                outcome=session_data.get("outcome", "SUCCESS"),
                errors=errors,
            )

            mem_id = str(uuid.uuid4())
            content = f"ReMe Strategy for {task_type}: {len(proc.steps)} steps"
            pack = self.compiler.compile(
                memory_id=mem_id,
                version=1,
                memory_type=MemoryType.PROCEDURE,
                content=content,
                data_json=proc.model_dump(),
            )
            ver_obj = MemoryVersion(
                memory_id=mem_id,
                version=1,
                content=content,
                data_json=proc.model_dump(),
                verification_status=VerificationStatus.VALIDATED,
                pack=pack,
                procedure_detail=proc,
            )
            mem_obj = Memory(id=mem_id, user_id=user_id, memory_type=MemoryType.PROCEDURE, status=MemoryStatus.DRAFT)
            await self.repo.create_memory(mem_obj, ver_obj)
            pub_ok = await self.publisher.publish(memory=mem_obj, version=ver_obj)
            if pub_ok:
                procedure_published = True

        return {
            "session_id": session_id,
            "facts_count": len(extracted_facts),
            "facts_published": facts_published,
            "procedure_published": procedure_published,
        }
