from typing import Any
from memory_service.domain.memory import MemoryVersion


class ApplicabilityFilter:
    """
    适用边界前置硬过滤门禁：
    对候选召回的经验与事实，根据当前会话运行时上下文 (Context) 执行适用性校验：
    - 市场/标的范围不匹配（如 A股 vs 美股）
    - 工具链不匹配
    - 用户等级/权限不匹配
    不符合当前上下文的记忆直接剔除，杜绝错误指导大模型决策。
    """

    def filter(
        self,
        candidates: list[MemoryVersion],
        context: dict[str, Any] | None = None,
    ) -> list[MemoryVersion]:
        if not context:
            return candidates

        filtered: list[MemoryVersion] = []
        for ver in candidates:
            if not self._is_applicable(ver, context):
                continue
            filtered.append(ver)

        return filtered

    def _is_applicable(self, version: MemoryVersion, context: dict[str, Any]) -> bool:
        if not version.procedure_detail:
            # 事实与偏好默认适用，除非有明确 context 限制
            return True

        proc = version.procedure_detail
        app_bounds = proc.applicability or {}

        # 1. 市场约束匹配
        ctx_market = context.get("market")
        app_market = app_bounds.get("market")
        if ctx_market and app_market and ctx_market != app_market:
            return False

        # 2. 工具链支持匹配
        ctx_tool = context.get("tool_name")
        supported_tools = app_bounds.get("supported_tools", [])
        if ctx_tool and supported_tools and ctx_tool not in supported_tools:
            return False

        # 3. 业务领域匹配
        ctx_domain = context.get("domain")
        app_domain = app_bounds.get("domain")
        if ctx_domain and app_domain and ctx_domain != app_domain:
            return False

        return True
