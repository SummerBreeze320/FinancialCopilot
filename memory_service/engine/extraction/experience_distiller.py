from typing import Any
from memory_service.domain.episode import Episode
from memory_service.domain.procedure import Procedure
from memory_service.domain.evidence import Evidence
from memory_service.domain.enums import EvidenceType


class ExperienceDistiller:
    """
    执行经验提炼器：
    从经历中间体 (Episode) 或历史执行日志中提炼出可复用的过程性经验 (Procedure)。
    """

    def distill_from_episode(self, episode: Episode) -> tuple[Procedure | None, Evidence | None]:
        details = episode.details_json
        tool_calls = details.get("tool_calls", [])
        errors = details.get("errors", [])

        if not tool_calls and not details.get("workflow_steps"):
            return None, None

        task_type = episode.task_type or "general_task"

        # 提炼执行步骤为规范字符串列表
        steps: list[str] = []
        if details.get("workflow_steps"):
            for s in details["workflow_steps"]:
                steps.append(str(s) if not isinstance(s, dict) else f"{s.get('action')}: {s.get('description', '')}")
        else:
            for idx, call in enumerate(tool_calls, 1):
                name = call.get("tool_name", call.get("name", f"action_{idx}"))
                args = call.get("arguments", call.get("args", {}))
                arg_keys = list(args.keys()) if isinstance(args, dict) else []
                steps.append(f"Step {idx}: Invoke {name}({', '.join(arg_keys)})")

        # 提炼前置安全与业务条件
        preconditions: list[str] = []
        if any(k in task_type for k in ["investment", "trade", "rebalance"]):
            preconditions.extend([
                "Check user KYC and investment risk level matches fund risk level",
                "Ensure target market is within trading hours",
                "Validate sufficient available balance",
            ])
        elif any(k in task_type for k in ["diagnosis", "portfolio"]):
            preconditions.extend([
                "Verify user portfolio data is synced and non-empty",
                "Validate fund net asset value (NAV) is up to date",
            ])
        else:
            preconditions.append("Verify user session authentication")

        # 提炼触发条件列表
        trigger_conditions = [
            f"task_type:{task_type}",
            f"keywords:{task_type.replace('_', ' ')},执行,分析,调仓,查询",
        ]

        # 适用边界字典
        applicability = {
            "domain": "finance",
            "market": "CN_A_SHARE_FUNDS",
            "supported_tools": [call.get("tool_name", call.get("name", "")) for call in tool_calls],
        }

        # 提炼已知错误特征与恢复机制
        failure_signatures: list[str] = []
        recovery_actions: list[str] = []

        if errors:
            for err in errors:
                err_code = err.get("code", "EXECUTION_ERROR")
                err_msg = err.get("message", "unknown error")
                failure_signatures.append(f"[{err_code}] {err_msg[:100]}")

                if "TIMEOUT" in err_code:
                    recovery_actions.append(f"If {err_code}: Retry with exponential backoff (max 2 attempts)")
                elif "RISK" in err_code:
                    recovery_actions.append(f"If {err_code}: Prompt user for risk re-assessment or choose lower risk tier")
                else:
                    recovery_actions.append(f"If {err_code}: Fallback to manual confirmation flow")

        procedure = Procedure(
            task_type=task_type,
            trigger_conditions=trigger_conditions,
            preconditions=preconditions,
            steps=steps,
            applicability=applicability,
            validation_rules=["Verify final output contains non-empty result"],
            failure_signatures=failure_signatures,
            recovery_actions=recovery_actions,
        )

        evidence = Evidence(
            memory_id="",
            version=1,
            evidence_type=EvidenceType.EXECUTION_TRACE,
            source_ref=episode.source_ref,
            payload={"episode_id": episode.id, "outcome": episode.outcome},
            confidence=1.0 if episode.outcome == "SUCCESS" else 0.7,
        )

        return procedure, evidence
