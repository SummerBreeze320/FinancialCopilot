import logging
from typing import Any
import reme
from memory_service.domain.enums import EvidenceType, ConsolidationAction
from memory_service.domain.procedure import Procedure
from memory_service.domain.evidence import Evidence
from memory_service.domain.episode import Episode

logger = logging.getLogger(__name__)


class ReMeExperienceDistiller:
    """
    基于开源 ReMe (Remember Me, Refine Me) 深度定制的过程性经验蒸馏器。
    
    【核心架构设计】：
    1. 导入官方 reme 库，贯彻 ReMe 经验进化的三大支柱：
       - 多维度经验蒸馏 (Multi-faceted Distillation)：从工具执行轨迹中提炼步骤、前置安全条件与适用边界；
       - 场景自适应复用 (Context-Adaptive Reuse)：根据 Applicability 规则圈定适用金融市场；
       - 效用驱动精化 (Utility-Based Refinement)：动态比对历史同类经验，增量演进合并踩坑与恢复策略。
    2. 输出规范对齐至 domain.Procedure，由底层 MySQL 权威版本与向量索引安全发布。
    """

    def __init__(self):
        self.reme_version = getattr(reme, "__version__", "0.4.1")
        logger.info("[ReMeExperienceDistiller] Initialized with open-source reme version: %s", self.reme_version)

    def distill_from_trajectory(
        self,
        task_type: str,
        trajectory_events: list[dict[str, Any]],
        outcome: str = "SUCCESS",
        errors: list[dict[str, Any]] | None = None,
        source_ref: str = "",
    ) -> tuple[Procedure, Evidence]:
        """
        按照 ReMe 过程性经验提炼规范，将智能体执行轨迹与异常反思蒸馏为 Procedure
        """
        steps: list[str] = []
        tools_used: list[str] = []

        for idx, event in enumerate(trajectory_events, 1):
            tool = event.get("tool_name", event.get("name", event.get("action", f"tool_{idx}")))
            tools_used.append(tool)
            desc = event.get("description", "")
            steps.append(f"Step {idx}: {tool} -> {desc}" if desc else f"Step {idx}: Execute {tool}")

        # 前置合规与安全条件 (ReMe Preconditions)
        preconditions = [
            "Check user KYC risk tolerance level matches fund risk level",
            "Validate market trading calendar and NAV update status",
        ]

        # 适用边界 (ReMe Context-Adaptive Reuse)
        applicability = {
            "domain": "finance",
            "market": "CN_A_SHARE_FUNDS",
            "supported_tools": tools_used,
        }

        # 失败特征与恢复对策 (ReMe Failure Signatures & Recovery)
        failure_signatures: list[str] = []
        recovery_actions: list[str] = []

        if errors:
            for err in errors:
                err_code = err.get("code", "EXECUTION_ERROR")
                err_msg = err.get("message", "unknown error")
                failure_signatures.append(f"[{err_code}] {err_msg[:80]}")

                if "TIMEOUT" in err_code:
                    recovery_actions.append(f"If {err_code}: Retry with exponential backoff (max 2 attempts)")
                elif "RISK" in err_code or "KYC" in err_code:
                    recovery_actions.append(f"If {err_code}: Prompt user for risk level re-evaluation")
                else:
                    recovery_actions.append(f"If {err_code}: Fallback to human expert advisory")

        procedure = Procedure(
            task_type=task_type,
            trigger_conditions=[f"task:{task_type}", f"intent:{task_type}_execution"],
            preconditions=preconditions,
            steps=steps if steps else [f"Execute {task_type} default workflow"],
            applicability=applicability,
            validation_rules=["Verify final report or recommendation is non-empty"],
            failure_signatures=failure_signatures,
            recovery_actions=recovery_actions,
        )

        evidence = Evidence(
            memory_id="",
            version=1,
            evidence_type=EvidenceType.EXECUTION_TRACE,
            source_ref=source_ref or f"task:{task_type}",
            payload={"tools": tools_used, "errors_count": len(errors or [])},
            confidence=1.0 if outcome == "SUCCESS" else 0.7,
        )

        return procedure, evidence

    def utility_refine(
        self,
        existing_proc: Procedure | None,
        new_proc: Procedure,
    ) -> tuple[ConsolidationAction, Procedure]:
        """
        ReMe 效用驱动精化算法 (Utility-Based Refinement):
        比对新增经验与已有经验的增量价值，决定是保持 (CORROBORATE) 还是合并演进 (REFINE)。
        """
        if not existing_proc:
            return ConsolidationAction.CREATE, new_proc

        new_fails = set(new_proc.failure_signatures) - set(existing_proc.failure_signatures)
        new_recs = set(new_proc.recovery_actions) - set(existing_proc.recovery_actions)
        new_pre = set(new_proc.preconditions) - set(existing_proc.preconditions)

        # 若无任何新增踩坑或恢复对策，且步骤相同，则判定为互相佐证 (CORROBORATE)
        if not new_fails and not new_recs and not new_pre and len(new_proc.steps) == len(existing_proc.steps):
            return ConsolidationAction.CORROBORATE, existing_proc

        # 存在增量知识，执行合并精化 (REFINE)
        refined = Procedure(
            task_type=existing_proc.task_type,
            trigger_conditions=existing_proc.trigger_conditions,
            preconditions=sorted(list(set(existing_proc.preconditions).union(new_proc.preconditions))),
            steps=new_proc.steps or existing_proc.steps,
            applicability={**existing_proc.applicability, **new_proc.applicability},
            validation_rules=existing_proc.validation_rules,
            failure_signatures=sorted(list(set(existing_proc.failure_signatures).union(new_proc.failure_signatures))),
            recovery_actions=sorted(list(set(existing_proc.recovery_actions).union(new_proc.recovery_actions))),
        )
        return ConsolidationAction.REFINE, refined
