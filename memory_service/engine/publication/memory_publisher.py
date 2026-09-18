import logging
from typing import Any
from memory_service.domain.enums import MemoryStatus, VerificationStatus, MemoryType
from memory_service.domain.memory import Memory, MemoryVersion
from memory_service.domain.fact import FactSlot
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.infrastructure.milvus.vector_repository import VectorRepository

logger = logging.getLogger(__name__)


class PublicationError(Exception):
    """记忆发布失败异常"""
    pass


class MemoryPublisher:
    """
    记忆发布门禁与原子切换器：
    保证在线 Reader 永远只读到经过完整验证、离线编译且索引就绪的不可变版本
    """

    def __init__(
        self,
        memory_repo: MemoryRepository,
        vector_repo: VectorRepository | None = None,
    ):
        self.memory_repo = memory_repo
        self.vector_repo = vector_repo or VectorRepository()

    async def publish(
        self,
        memory: Memory | str,
        version: MemoryVersion | int,
        embedding: list[float] | None = None,
        fact_key: str | None = None,
        slot_value: Any = None,
    ) -> bool:
        """
        核心发布流程：
        1. 门禁校验：状态必须为 VALIDATED 且已编译 Pack
        2. 写入 Milvus 向量索引 (带有版本标识)
        3. MySQL 事务原子更新 published_version 指针并置为 ACTIVE
        4. 若属于单值事实，原子更新事实槽位 (Fact Slot)
        """
        # 支持传入实体或ID
        if isinstance(memory, str):
            mem_obj = await self.memory_repo.get_memory(memory)
            if not mem_obj:
                raise PublicationError(f"Memory {memory} not found")
        else:
            mem_obj = memory

        if isinstance(version, int):
            ver_obj = await self.memory_repo.get_version(mem_obj.id, version)
            if not ver_obj:
                raise PublicationError(f"MemoryVersion {mem_obj.id}:v{version} not found")
        else:
            ver_obj = version

        # 1. 门禁校验
        if ver_obj.verification_status != VerificationStatus.VALIDATED:
            raise PublicationError(
                f"Memory version {ver_obj.memory_id}:v{ver_obj.version} cannot be published: "
                f"verification_status is {ver_obj.verification_status.value} (must be VALIDATED)"
            )

        if not ver_obj.pack:
            raise PublicationError(
                f"Memory version {ver_obj.memory_id}:v{ver_obj.version} cannot be published: "
                f"MemoryPack has not been compiled yet"
            )

        # 2. 写入 Milvus 向量索引
        if embedding:
            task_type = ver_obj.procedure_detail.task_type if ver_obj.procedure_detail else "GENERAL"
            await self.vector_repo.upsert_vector(
                memory_id=mem_obj.id,
                memory_version=ver_obj.version,
                user_id=mem_obj.user_id,
                memory_type=mem_obj.memory_type.value,
                task_type=task_type,
                embedding=embedding,
            )
            logger.info("Synchronized Milvus vector index for %s:v%s", mem_obj.id, ver_obj.version)

        # 3. MySQL 事务原子切换发布指针
        success = await self.memory_repo.switch_published_version(mem_obj.id, ver_obj.version)
        if not success:
            raise PublicationError(f"Failed to update published_version pointer for memory {mem_obj.id}")

        # 4. 若为单值事实，同步刷新槽位
        if mem_obj.memory_type in (MemoryType.FACT, MemoryType.PREFERENCE) and fact_key:
            val = slot_value if slot_value is not None else ver_obj.data_json.get("value", ver_obj.data_json.get("object_value"))
            slot = FactSlot(
                user_id=mem_obj.user_id,
                fact_key=fact_key,
                memory_id=mem_obj.id,
                current_version=ver_obj.version,
                value=val,
            )
            await self.memory_repo.upsert_fact_slot(slot)
            logger.info("Updated fact slot %s for user %s to memory %s:v%s", fact_key, mem_obj.user_id, mem_obj.id, ver_obj.version)

        logger.info("Successfully published memory %s at version %s", mem_obj.id, ver_obj.version)
        return True
