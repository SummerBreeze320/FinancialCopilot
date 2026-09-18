from memory_service.domain.fact import FactSlot
from memory_service.domain.memory import MemoryVersion
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository


class ExactRetriever:
    """
    L0 精确匹配检索器：
    利用 MySQL 唯一事实槽位表 (ltm_fact_slot)，秒级返回用户当前最新生效的事实与偏好。
    完全免除向量索引与大模型开销。
    """

    def __init__(self, memory_repo: MemoryRepository):
        self.repo = memory_repo

    async def retrieve(
        self,
        user_id: str,
        fact_keys: list[str] | None = None,
    ) -> list[tuple[FactSlot, MemoryVersion]]:
        """
        检索指定或全量的事实槽位及对应的当前发布版本快照
        """
        results: list[tuple[FactSlot, MemoryVersion]] = []

        if fact_keys:
            slots = []
            for k in fact_keys:
                s = await self.repo.get_fact_slot(user_id, k)
                if s:
                    slots.append(s)
        else:
            slots = await self.repo.list_user_fact_slots(user_id)

        for slot in slots:
            ver = await self.repo.get_version(slot.memory_id, slot.current_version)
            if ver and ver.pack:
                results.append((slot, ver))

        return results
