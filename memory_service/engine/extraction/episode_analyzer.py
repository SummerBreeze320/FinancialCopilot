from typing import Any
import uuid
from memory_service.domain.episode import Episode


class EpisodeAnalyzer:
    """
    单次交互经历分析器：
    将整场会话分析提炼为离线经历中间体 (Episode)。
    识别会话的意图任务类型、执行流程、工具调用结果以及最终是成功、失败还是部分成功。
    """

    def analyze(
        self,
        session_id: str,
        user_id: str,
        session_data: dict[str, Any],
    ) -> Episode:
        messages = session_data.get("messages", [])
        tool_calls = session_data.get("tool_calls", [])
        error_logs = session_data.get("errors", [])
        explicit_outcome = session_data.get("outcome")

        # 1. 识别任务类型
        task_type = session_data.get("task_type")
        if not task_type:
            # 从用户首次消息或工具调用中推断
            first_user_msg = next((m.get("content", "") for m in messages if m.get("role") == "user"), "")
            if any(k in first_user_msg for k in ["诊断", "测评", "分析持仓"]):
                task_type = "portfolio_diagnosis"
            elif any(k in first_user_msg for k in ["定投", "申购", "买入", "交易"]):
                task_type = "investment_execution"
            elif any(k in first_user_msg for k in ["推荐", "选基", "挑基金"]):
                task_type = "fund_recommendation"
            elif any(k in first_user_msg for k in ["调仓", "再平衡"]):
                task_type = "portfolio_rebalance"
            else:
                task_type = "financial_qa"

        # 2. 判定结果
        if explicit_outcome:
            outcome = explicit_outcome.upper()
        elif error_logs:
            # 存在错误，判断是否有恢复重试成功
            has_recovered = session_data.get("has_recovered", False)
            outcome = "SUCCESS" if has_recovered else "FAILURE"
        else:
            outcome = "SUCCESS"

        # 3. 生成摘要
        summary = session_data.get("summary")
        if not summary:
            first_msg = next((m.get("content", "") for m in messages if m.get("role") == "user"), "会话交互")
            summary = f"用户执行 {task_type} 任务: {first_msg[:80]}..."

        details = {
            "session_id": session_id,
            "message_count": len(messages),
            "tool_calls": tool_calls,
            "errors": error_logs,
            "metadata": session_data.get("metadata", {}),
        }

        return Episode(
            id=str(uuid.uuid4()),
            user_id=user_id,
            source_ref=f"session:{session_id}",
            task_type=task_type,
            summary=summary,
            outcome=outcome,
            details_json=details,
        )
