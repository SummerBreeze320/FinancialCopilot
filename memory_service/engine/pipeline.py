import uuid
from typing import Any
from memory_service.domain.enums import (
    MemoryType,
    MemoryStatus,
    VerificationStatus,
    ConsolidationAction,
)
from memory_service.domain.memory import Memory, MemoryVersion
from memory_service.domain.fact import FactSlot
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.extraction.fact_extractor import FactExtractor
from memory_service.engine.extraction.episode_analyzer import EpisodeAnalyzer
from memory_service.engine.extraction.experience_distiller import ExperienceDistiller
from memory_service.engine.verification.fact_verifier import FactVerifier
from memory_service.engine.verification.experience_validator import ExperienceValidator
from memory_service.engine.consolidation.fact_resolver import FactResolver
from memory_service.engine.consolidation.experience_consolidator import ExperienceConsolidator
from memory_service.engine.compilation.memory_compiler import MemoryCompiler
from memory_service.engine.publication.memory_publisher import MemoryPublisher


class OfflineMemoryPipeline:
    """
    离线记忆加工流水线总控编排：
    输入：已准备好的会话/执行数据
    执行严密的 6 步离线处理闭环：
    1. 交互切片与经历提炼 (Episode Analysis)
    2. 原子事实与偏好提取 (Fact Extraction)
    3. 过程性经验提炼 (Experience Distillation)
    4. 冲突消解与版本去重 (Consolidation & Conflict Resolution)
    5. 事实与经验强约束校验 (Fact & Experience Verification Gate)
    6. 预编译在线物化包 (Materialized Pack Compilation)
    7. 准入发布与双写同步 (Safe Publication Gate)
    """

    def __init__(
        self,
        memory_repo: MemoryRepository,
        publisher: MemoryPublisher,
        fact_extractor: FactExtractor | None = None,
        episode_analyzer: EpisodeAnalyzer | None = None,
        experience_distiller: ExperienceDistiller | None = None,
        fact_verifier: FactVerifier | None = None,
        experience_validator: ExperienceValidator | None = None,
        fact_resolver: FactResolver | None = None,
        experience_consolidator: ExperienceConsolidator | None = None,
        compiler: MemoryCompiler | None = None,
    ):
        self.repo = memory_repo
        self.publisher = publisher
        self.fact_extractor = fact_extractor or FactExtractor()
        self.episode_analyzer = episode_analyzer or EpisodeAnalyzer()
        self.experience_distiller = experience_distiller or ExperienceDistiller()
        self.fact_verifier = fact_verifier or FactVerifier()
        self.experience_validator = experience_validator or ExperienceValidator()
        self.fact_resolver = fact_resolver or FactResolver()
        self.experience_consolidator = experience_consolidator or ExperienceConsolidator()
        self.compiler = compiler or MemoryCompiler()

    async def process_session(
        self,
        session_id: str,
        user_id: str,
        session_data: dict[str, Any],
    ) -> dict[str, Any]:
        """执行完整离线记忆加工流程"""
        # 1. 保存 Episode 溯源
        episode = self.episode_analyzer.analyze(session_id, user_id, session_data)
        await self.repo.save_episode(episode)

        facts_published_count = 0
        procedure_published = False

        # 2. 事实与偏好处理流
        facts, evidences = self.fact_extractor.extract_from_session(session_id, user_id, session_data)
        for idx, fact in enumerate(facts):
            evidence = evidences[idx] if idx < len(evidences) else None

            # 校验门禁
            v_status, _ = self.fact_verifier.verify(fact)
            if v_status != VerificationStatus.VALIDATED:
                continue

            # 读取当前槽位并消解冲突
            existing_slot = await self.repo.get_fact_slot(user_id, fact.predicate)
            resolution = self.fact_resolver.resolve(fact, existing_slot)

            if resolution.action == ConsolidationAction.CORROBORATE:
                continue

            m_type = (
                MemoryType.PREFERENCE
                if any(k in fact.predicate for k in ["preference", "excluded", "preferred", "favorite"])
                else MemoryType.FACT
            )

            if resolution.action == ConsolidationAction.CREATE:
                mem_id = str(uuid.uuid4())
                content = f"User {fact.predicate} is {resolution.resolved_value}"
                pack = self.compiler.compile(
                    memory_id=mem_id,
                    version=1,
                    memory_type=m_type,
                    content=content,
                    data_json={
                        "subject": fact.subject,
                        "predicate": fact.predicate,
                        "value": resolution.resolved_value,
                    },
                )
                if evidence:
                    evidence.memory_id = mem_id
                    evidence.version = 1

                version_obj = MemoryVersion(
                    memory_id=mem_id,
                    version=1,
                    content=content,
                    data_json=fact.model_dump(),
                    verification_status=VerificationStatus.VALIDATED,
                    pack=pack,
                    evidences=[evidence] if evidence else [],
                    fact_detail=fact,
                )
                memory_obj = Memory(
                    id=mem_id,
                    user_id=user_id,
                    memory_type=m_type,
                    status=MemoryStatus.DRAFT,
                )
                await self.repo.create_memory(memory_obj, version_obj)

                new_slot = FactSlot(
                    user_id=user_id,
                    fact_key=fact.predicate,
                    memory_id=mem_id,
                    current_version=1,
                    value=resolution.resolved_value,
                )
                pub_ok = await self.publisher.publish(
                    memory=memory_obj,
                    version=version_obj,
                    fact_key=fact.predicate,
                    slot_value=resolution.resolved_value,
                )
                if pub_ok:
                    facts_published_count += 1

            elif resolution.action in (ConsolidationAction.REFINE, ConsolidationAction.CORRECT):
                mem_id = resolution.target_memory_id
                if not mem_id:
                    continue

                latest_v = await self.repo.get_latest_version_num(mem_id)
                new_v = latest_v + 1
                content = f"User {fact.predicate} updated to {resolution.resolved_value}"
                pack = self.compiler.compile(
                    memory_id=mem_id,
                    version=new_v,
                    memory_type=m_type,
                    content=content,
                    data_json={
                        "subject": fact.subject,
                        "predicate": fact.predicate,
                        "value": resolution.resolved_value,
                    },
                )
                if evidence:
                    evidence.memory_id = mem_id
                    evidence.version = new_v

                version_obj = MemoryVersion(
                    memory_id=mem_id,
                    version=new_v,
                    content=content,
                    data_json=fact.model_dump(),
                    verification_status=VerificationStatus.VALIDATED,
                    pack=pack,
                    evidences=[evidence] if evidence else [],
                    fact_detail=fact,
                )
                await self.repo.add_version(version_obj)

                pub_ok = await self.publisher.publish(
                    memory=mem_id,
                    version=version_obj,
                    fact_key=fact.predicate,
                    slot_value=resolution.resolved_value,
                )
                if pub_ok:
                    facts_published_count += 1

        # 3. 过程性经验处理流
        proc, p_evidence = self.experience_distiller.distill_from_episode(episode)
        if proc:
            p_status, _ = self.experience_validator.validate(proc)
            if p_status == VerificationStatus.VALIDATED:
                existing_procs = await self.repo.list_active_memories_by_type(
                    user_id=user_id, memory_type=MemoryType.PROCEDURE
                )
                matched_mem_id = None
                matched_proc_detail = None

                for active_m in existing_procs:
                    if active_m.published_version:
                        ver_detail = await self.repo.get_version(active_m.id, active_m.published_version)
                        if ver_detail and ver_detail.procedure_detail:
                            if ver_detail.procedure_detail.task_type == proc.task_type:
                                matched_mem_id = active_m.id
                                matched_proc_detail = ver_detail.procedure_detail
                                break

                c_result = self.experience_consolidator.consolidate(
                    new_proc=proc,
                    existing_proc=matched_proc_detail,
                    existing_memory_id=matched_mem_id,
                )

                if c_result.action == ConsolidationAction.CREATE:
                    mem_id = str(uuid.uuid4())
                    content = f"Procedure for {proc.task_type}: {len(proc.steps)} steps workflow"
                    pack = self.compiler.compile(
                        memory_id=mem_id,
                        version=1,
                        memory_type=MemoryType.PROCEDURE,
                        content=content,
                        data_json=proc.model_dump(),
                    )
                    if p_evidence:
                        p_evidence.memory_id = mem_id
                        p_evidence.version = 1

                    ver_obj = MemoryVersion(
                        memory_id=mem_id,
                        version=1,
                        content=content,
                        data_json=proc.model_dump(),
                        verification_status=VerificationStatus.VALIDATED,
                        pack=pack,
                        evidences=[p_evidence] if p_evidence else [],
                        procedure_detail=proc,
                    )
                    mem_obj = Memory(
                        id=mem_id,
                        user_id=user_id,
                        memory_type=MemoryType.PROCEDURE,
                        status=MemoryStatus.DRAFT,
                    )
                    await self.repo.create_memory(mem_obj, ver_obj)
                    pub_ok = await self.publisher.publish(memory=mem_obj, version=ver_obj)
                    if pub_ok:
                        procedure_published = True

                elif c_result.action == ConsolidationAction.REFINE:
                    target_id = c_result.target_memory_id
                    if target_id:
                        latest_v = await self.repo.get_latest_version_num(target_id)
                        new_v = latest_v + 1
                        c_proc = c_result.consolidated_procedure
                        content = f"Procedure for {c_proc.task_type} (v{new_v}): enhanced with failure recovery"
                        pack = self.compiler.compile(
                            memory_id=target_id,
                            version=new_v,
                            memory_type=MemoryType.PROCEDURE,
                            content=content,
                            data_json=c_proc.model_dump(),
                        )
                        if p_evidence:
                            p_evidence.memory_id = target_id
                            p_evidence.version = new_v

                        ver_obj = MemoryVersion(
                            memory_id=target_id,
                            version=new_v,
                            content=content,
                            data_json=c_proc.model_dump(),
                            verification_status=VerificationStatus.VALIDATED,
                            pack=pack,
                            evidences=[p_evidence] if p_evidence else [],
                            procedure_detail=c_proc,
                        )
                        await self.repo.add_version(ver_obj)
                        pub_ok = await self.publisher.publish(memory=target_id, version=ver_obj)
                        if pub_ok:
                            procedure_published = True

        return {
            "session_id": session_id,
            "user_id": user_id,
            "episode_id": episode.id,
            "facts_extracted": len(facts),
            "facts_published": facts_published_count,
            "procedure_published": procedure_published,
        }
