import json
from typing import Any
from memory_service.domain.procedure import Procedure
from memory_service.domain.enums import ConsolidationAction


class ReMeExperienceAdapter:
    """
    ReMe (Remember Me, Refine Me) 过程性经验适配器：
    
    【核心评估结论】：
    1. ReMe 默认以 "Memory as File" 为理念，在本地磁盘维护 Markdown 文件和 BM25 索引。
       在金融微服务生产架构中，无法直接让其接管本地磁盘（缺少分布式事务、悲观并发控制与双写保障）。
    2. 本适配器复用 ReMe 的核心经验提炼三大支柱：
       - 多维度经验蒸馏 (Multi-faceted Distillation)：从执行轨迹提炼 Strategy、Pitfalls 和 Recovery
       - 场景自适应复用 (Context-Adaptive Reuse)：根据 Applicability 规则精确圈定复用边界
       - 效用驱动精化 (Utility-Based Refinement)：对同类经验进行去芜存菁，演进合并
    3. 输出：统一对接 domain.Procedure 模型，入库交由 MySQL 权威版本与 Milvus 向量引擎管理。
    """

    REME_DISTILLATION_PROMPT = """
You are an expert procedural memory distiller following the ReMe (Remember Me, Refine Me) framework.
Analyze the execution trajectory and extract:
1. task_type: Standard task category
2. trigger_conditions: Usage scenarios and keywords
3. preconditions: Mandatory safety/compliance checks before execution
4. steps: Recommended execution strategies
5. applicability: Scenario constraints (market, toolset, domain)
6. failure_signatures: Pitfalls and errors encountered
7. recovery_actions: Effective recovery remedies
"""

    def distill_trajectory(
        self,
        task_type: str,
        trajectory_events: list[dict[str, Any]],
        outcome: str = "SUCCESS",
        errors: list[dict[str, Any]] | None = None,
    ) -> Procedure:
        """
        按照 ReMe 多维度提炼思想，将执行事件轨迹提炼为 Procedure
        """
        steps: list[str] = []
        tools_used: list[str] = []

        for idx, event in enumerate(trajectory_events, 1):
            tool = event.get("tool_name", event.get("action", f"tool_{idx}"))
            tools_used.append(tool)
            desc = event.get("description", "")
            steps.append(f"Step {idx}: {tool} -> {desc}" if desc else f"Step {idx}: Execute {tool}")

        # 提炼前置条件（结合 ReMe 的安全边界原则）
        preconditions = [
            "Verify user session and KYC risk level",
            "Validate target asset within market operating schedule",
        ]

        # 提炼适用边界（ReMe 上下文适配原则）
        applicability = {
            "domain": "finance",
            "market": "CN_A_SHARE_FUNDS",
            "supported_tools": tools_used,
        }

        # 提炼失败陷阱与恢复策略（ReMe 踩坑与反思原则）
        failure_signatures: list[str] = []
        recovery_actions: list[str] = []

        if errors:
            for err in errors:
                err_code = err.get("code", "ERROR")
                err_msg = err.get("message", "")
                failure_signatures.append(f"[{err_code}] {err_msg[:80]}")

                if "TIMEOUT" in err_code:
                    recovery_actions.append(f"If {err_code}: Retry with exponential backoff")
                elif "RISK" in err_code or "KYC" in err_code:
                    recovery_actions.append(f"If {err_code}: Prompt user for KYC risk re-evaluation")
                else:
                    recovery_actions.append(f"If {err_code}: Route to human agent fallback")

        return Procedure(
            task_type=task_type,
            trigger_conditions=[f"task:{task_type}", "intent:execution"],
            preconditions=preconditions,
            steps=steps,
            applicability=applicability,
            validation_rules=["Ensure execution output non-empty and verified"],
            failure_signatures=failure_signatures,
            recovery_actions=recovery_actions,
        )

    def utility_refine(
        self,
        existing_proc: Procedure | None,
        new_proc: Procedure,
    ) -> tuple[ConsolidationAction, Procedure]:
        """
        ReMe 效用驱动精化 (Utility-Based Refinement)：
        判断新提炼的经验相较于已有经验是否具有新增效用（更多错误对策、更新的执行路径）：
        - 若覆盖且无增量：CORROBORATE (保持原样)
        - 若有新增踩坑经验/对策：REFINE (合并演进)
        """
        if not existing_proc:
            return ConsolidationAction.CREATE, new_proc

        new_fails = set(new_proc.failure_signatures) - set(existing_proc.failure_signatures)
        new_recs = set(new_proc.recovery_actions) - set(existing_proc.recovery_actions)
        new_pre = set(new_proc.preconditions) - set(existing_proc.preconditions)

        if not new_fails and not new_recs and not new_pre and len(new_proc.steps) == len(existing_proc.steps):
            return ConsolidationAction.CORROBORATE, existing_proc

        # 合并精化
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
