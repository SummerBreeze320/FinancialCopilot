from typing import Any
from pydantic import BaseModel, Field
from memory_service.domain.memory import MemoryVersion


class RecalledMemoryItem(BaseModel):
    memory_id: str
    version: int
    content: str
    compact_text: str
    token_count: int


class MemoryBundle(BaseModel):
    """
    在线记忆拼装物料包：
    直接注入到 LLM 系统提示词中，具备零运行时 Tokenizer 开销与严格的 Token 预算保护。
    """
    compact_context: str = Field(description="编译好的 Prompt 注入上下文块")
    recalled_items: list[RecalledMemoryItem] = Field(default_factory=list, description="召回的明细项快照")
    total_tokens: int = Field(default=0, description="已使用的 Token 总数")
    token_budget: int = Field(default=500, description="设定的 Token 预算上限")


class ContextAssembler:
    """
    在线 Token 预算拼装器：
    利用离线预编译好的 MemoryPack.token_count，以贪心/优先级策略拼装记忆，确保严守 Token 预算。
    """

    def assemble(
        self,
        versions: list[MemoryVersion],
        token_budget: int = 500,
        use_compact: bool = True,
    ) -> MemoryBundle:
        seen_memory_ids: set[str] = set()
        chosen_items: list[RecalledMemoryItem] = []
        current_tokens = 0

        # 系统提示词基础标题的预估 token
        header_text = "### Long-Term Memory Context & Constraints\n"
        header_tokens = 10
        current_tokens += header_tokens

        lines: list[str] = []

        for ver in versions:
            if ver.memory_id in seen_memory_ids:
                continue
            if not ver.pack:
                continue

            text_to_use = ver.pack.compact_text if use_compact else ver.pack.standard_text
            item_tokens = ver.pack.token_count

            # 预算检查
            if current_tokens + item_tokens > token_budget:
                continue

            seen_memory_ids.add(ver.memory_id)
            current_tokens += item_tokens
            lines.append(f"- {text_to_use}")

            chosen_items.append(
                RecalledMemoryItem(
                    memory_id=ver.memory_id,
                    version=ver.version,
                    content=ver.content,
                    compact_text=ver.pack.compact_text,
                    token_count=item_tokens,
                )
            )

        if lines:
            compact_context = header_text + "\n".join(lines)
        else:
            compact_context = ""

        return MemoryBundle(
            compact_context=compact_context,
            recalled_items=chosen_items,
            total_tokens=current_tokens if lines else 0,
            token_budget=token_budget,
        )
