import json
from datetime import datetime, timezone
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from memory_service.domain.enums import MemoryStatus, MemoryType, VerificationStatus
from memory_service.domain.memory import Memory, MemoryVersion
from memory_service.domain.fact import FactSlot
from memory_service.domain.procedure import Procedure
from memory_service.domain.memory_pack import MemoryPack
from memory_service.domain.evidence import Evidence
from memory_service.domain.episode import Episode
from memory_service.infrastructure.mysql.models import (
    LtmMemoryModel,
    LtmMemoryVersionModel,
    LtmFactSlotModel,
    LtmProcedureModel,
    LtmEvidenceModel,
    LtmMemoryPackModel,
    LtmEpisodeModel,
)


class MemoryRepository:
    """长期记忆与不可变版本 MySQL 仓储"""

    def __init__(self, session: AsyncSession):
        self.session = session

    async def create_memory(self, memory: Memory, initial_version: MemoryVersion) -> Memory:
        """创建新记忆聚合与初始版本 v1"""
        mem_model = LtmMemoryModel(
            id=memory.id,
            user_id=memory.user_id,
            memory_type=memory.memory_type.value,
            status=memory.status.value,
            published_version=memory.published_version,
            created_at=memory.created_at,
            updated_at=memory.updated_at,
        )
        self.session.add(mem_model)

        await self._insert_version_record(initial_version)
        await self.session.flush()
        return memory

    async def append_version(self, new_version: MemoryVersion) -> MemoryVersion:
        """为现有记忆追加不可变版本 (如 v2, v3)"""
        await self._insert_version_record(new_version)
        await self.session.flush()
        return new_version

    # 别名兼容
    add_version = append_version

    async def _insert_version_record(self, version: MemoryVersion) -> None:
        """持久化版本主表及附属的 Procedure / Pack / Evidence 数据"""
        v_model = LtmMemoryVersionModel(
            memory_id=version.memory_id,
            version=version.version,
            content=version.content,
            data_json=version.data_json,
            verification_status=version.verification_status.value,
            valid_from=version.valid_from,
            valid_to=version.valid_to,
            created_at=version.created_at,
        )
        self.session.add(v_model)

        if version.procedure_detail:
            p = version.procedure_detail
            p_model = LtmProcedureModel(
                memory_id=version.memory_id,
                version=version.version,
                task_type=p.task_type,
                trigger_conditions_json=p.trigger_conditions,
                preconditions_json=p.preconditions,
                steps_json=p.steps,
                applicability_json=p.applicability,
                validation_rules_json=p.validation_rules,
                failure_signatures_json=p.failure_signatures,
                recovery_actions_json=p.recovery_actions,
            )
            self.session.add(p_model)

        if version.pack:
            pack = version.pack
            pack_model = LtmMemoryPackModel(
                memory_id=version.memory_id,
                version=version.version,
                compact_text=pack.compact_text,
                standard_text=pack.standard_text,
                retrieval_text=pack.retrieval_text,
                token_count=pack.token_count,
            )
            self.session.add(pack_model)

        for ev in version.evidences:
            ev_model = LtmEvidenceModel(
                id=ev.id,
                memory_id=version.memory_id,
                version=version.version,
                evidence_type=ev.evidence_type.value,
                source_ref=ev.source_ref,
                payload_json=ev.payload,
                confidence=ev.confidence,
                created_at=ev.created_at,
            )
            self.session.add(ev_model)

    async def get_memory(self, memory_id: str) -> Memory | None:
        """查询记忆根状态"""
        stmt = select(LtmMemoryModel).where(LtmMemoryModel.id == memory_id)
        result = await self.session.execute(stmt)
        model = result.scalar_one_or_none()
        if not model:
            return None
        return Memory(
            id=model.id,
            user_id=model.user_id,
            memory_type=MemoryType(model.memory_type),
            status=MemoryStatus(model.status),
            published_version=model.published_version,
            created_at=model.created_at,
            updated_at=model.updated_at,
        )

    async def get_version(self, memory_id: str, version_num: int) -> MemoryVersion | None:
        """读取特定版本及物化 Pack"""
        stmt = select(LtmMemoryVersionModel).where(
            LtmMemoryVersionModel.memory_id == memory_id,
            LtmMemoryVersionModel.version == version_num
        )
        result = await self.session.execute(stmt)
        vm = result.scalar_one_or_none()
        if not vm:
            return None

        # 查询 pack
        pack_stmt = select(LtmMemoryPackModel).where(
            LtmMemoryPackModel.memory_id == memory_id,
            LtmMemoryPackModel.version == version_num
        )
        pack_res = await self.session.execute(pack_stmt)
        pack_model = pack_res.scalar_one_or_none()
        pack = None
        if pack_model:
            pack = MemoryPack(
                memory_id=pack_model.memory_id,
                version=pack_model.version,
                compact_text=pack_model.compact_text,
                standard_text=pack_model.standard_text,
                retrieval_text=pack_model.retrieval_text,
                token_count=pack_model.token_count,
            )

        # 查询 procedure
        proc_stmt = select(LtmProcedureModel).where(
            LtmProcedureModel.memory_id == memory_id,
            LtmProcedureModel.version == version_num
        )
        proc_res = await self.session.execute(proc_stmt)
        proc_model = proc_res.scalar_one_or_none()
        procedure_detail = None
        if proc_model:
            procedure_detail = Procedure(
                task_type=proc_model.task_type,
                trigger_conditions=proc_model.trigger_conditions_json or [],
                preconditions=proc_model.preconditions_json or [],
                steps=proc_model.steps_json or [],
                applicability=proc_model.applicability_json or {},
                validation_rules=proc_model.validation_rules_json or [],
                failure_signatures=proc_model.failure_signatures_json or [],
                recovery_actions=proc_model.recovery_actions_json or [],
            )

        return MemoryVersion(
            memory_id=vm.memory_id,
            version=vm.version,
            content=vm.content,
            data_json=vm.data_json,
            verification_status=VerificationStatus(vm.verification_status),
            valid_from=vm.valid_from,
            valid_to=vm.valid_to,
            created_at=vm.created_at,
            pack=pack,
            procedure_detail=procedure_detail,
        )

    async def get_published_version(self, memory_id: str) -> MemoryVersion | None:
        """获取当前对在线服务生效的已发布版本"""
        mem = await self.get_memory(memory_id)
        if not mem or mem.published_version is None or mem.status != MemoryStatus.ACTIVE:
            return None
        return await self.get_version(memory_id, mem.published_version)

    async def switch_published_version(self, memory_id: str, new_version: int) -> bool:
        """
        原子切换发布指针到新版本，并激活记忆状态为 ACTIVE
        """
        stmt = (
            update(LtmMemoryModel)
            .where(LtmMemoryModel.id == memory_id)
            .values(
                published_version=new_version,
                status=MemoryStatus.ACTIVE.value,
                updated_at=datetime.now(timezone.utc),
            )
        )
        result = await self.session.execute(stmt)
        await self.session.flush()
        return result.rowcount > 0

    async def soft_delete_memory(self, memory_id: str) -> bool:
        """标记删除记忆（立即阻断在线召回）"""
        stmt = (
            update(LtmMemoryModel)
            .where(LtmMemoryModel.id == memory_id)
            .values(
                status=MemoryStatus.DELETED.value,
                updated_at=datetime.now(timezone.utc),
            )
        )
        result = await self.session.execute(stmt)
        await self.session.flush()
        return result.rowcount > 0

    async def upsert_fact_slot(self, slot: FactSlot) -> FactSlot:
        """
        悲观锁/CAS 更新事实槽位：锁定 user_id + fact_key 记录并演进
        """
        stmt = (
            select(LtmFactSlotModel)
            .where(
                LtmFactSlotModel.user_id == slot.user_id,
                LtmFactSlotModel.fact_key == slot.fact_key
            )
            .with_for_update()
        )
        result = await self.session.execute(stmt)
        existing = result.scalar_one_or_none()

        if existing:
            existing.memory_id = slot.memory_id
            existing.current_version = slot.current_version
            existing.value_json = slot.value
            existing.updated_at = datetime.now(timezone.utc)
        else:
            new_model = LtmFactSlotModel(
                user_id=slot.user_id,
                fact_key=slot.fact_key,
                memory_id=slot.memory_id,
                current_version=slot.current_version,
                value_json=slot.value,
                updated_at=slot.updated_at,
            )
            self.session.add(new_model)

        await self.session.flush()
        return slot

    async def get_fact_slot(self, user_id: str, fact_key: str) -> FactSlot | None:
        """根据用户和槽位 key 精确读取当前事实"""
        stmt = select(LtmFactSlotModel).where(
            LtmFactSlotModel.user_id == user_id,
            LtmFactSlotModel.fact_key == fact_key
        )
        result = await self.session.execute(stmt)
        model = result.scalar_one_or_none()
        if not model:
            return None
        return FactSlot(
            user_id=model.user_id,
            fact_key=model.fact_key,
            memory_id=model.memory_id,
            current_version=model.current_version,
            value=model.value_json,
            updated_at=model.updated_at,
        )

    async def list_user_fact_slots(self, user_id: str) -> list[FactSlot]:
        """列出用户的所有当前有效事实槽位"""
        stmt = select(LtmFactSlotModel).where(LtmFactSlotModel.user_id == user_id)
        result = await self.session.execute(stmt)
        return [
            FactSlot(
                user_id=m.user_id,
                fact_key=m.fact_key,
                memory_id=m.memory_id,
                current_version=m.current_version,
                value=m.value_json,
                updated_at=m.updated_at,
            )
            for m in result.scalars().all()
        ]

    async def list_active_memories_by_type(self, user_id: str, memory_type: MemoryType) -> list[Memory]:
        """查询用户指定类型的生效记忆聚合根列表"""
        stmt = select(LtmMemoryModel).where(
            LtmMemoryModel.user_id == user_id,
            LtmMemoryModel.memory_type == memory_type.value,
            LtmMemoryModel.status == MemoryStatus.ACTIVE.value,
        )
        result = await self.session.execute(stmt)
        return [
            Memory(
                id=m.id,
                user_id=m.user_id,
                memory_type=MemoryType(m.memory_type),
                status=MemoryStatus(m.status),
                published_version=m.published_version,
                created_at=m.created_at,
                updated_at=m.updated_at,
            )
            for m in result.scalars().all()
        ]

    async def get_latest_version_num(self, memory_id: str) -> int:
        """获取指定记忆聚合当前最大的版本号"""
        stmt = (
            select(LtmMemoryVersionModel.version)
            .where(LtmMemoryVersionModel.memory_id == memory_id)
            .order_by(LtmMemoryVersionModel.version.desc())
            .limit(1)
        )
        result = await self.session.execute(stmt)
        ver = result.scalar_one_or_none()
        return ver if ver is not None else 0

    async def save_episode(self, episode: Episode) -> None:
        """存储交互经历 Episode 供离线分析与溯源"""
        ep_model = LtmEpisodeModel(
            id=episode.id,
            user_id=episode.user_id,
            source_ref=episode.source_ref,
            task_type=episode.task_type,
            summary=episode.summary,
            outcome=episode.outcome,
            details_json=episode.details_json,
            created_at=episode.created_at,
        )
        self.session.add(ep_model)
        await self.session.flush()
