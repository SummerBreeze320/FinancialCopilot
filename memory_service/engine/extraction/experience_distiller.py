from typing import Any
from memory_service.domain.episode import Episode
from memory_service.domain.procedure import Procedure
from memory_service.domain.evidence import Evidence
from memory_service.domain.enums import EvidenceType
from memory_service.engine.extraction.reme_distiller import ReMeExperienceDistiller


class ExperienceDistiller:
    """
    执行经验提炼器：
    从经历中间体 (Episode) 或历史执行日志中提炼出可复用的过程性经验 (Procedure)。
    深度集成开源 ReMe (reme-ai) 动态过程性经验蒸馏协议。
    """

    def __init__(self, reme_distiller: ReMeExperienceDistiller | None = None):
        self.reme_distiller = reme_distiller or ReMeExperienceDistiller()

    def distill_from_episode(self, episode: Episode) -> tuple[Procedure | None, Evidence | None]:
        details = episode.details_json
        tool_calls = details.get("tool_calls", [])
        errors = details.get("errors", [])

        if not tool_calls and not details.get("workflow_steps"):
            return None, None

        task_type = episode.task_type or "general_task"
        events = tool_calls or details.get("workflow_steps", [])
        return self.reme_distiller.distill_from_trajectory(
            task_type=task_type,
            trajectory_events=events,
            outcome=episode.outcome,
            errors=errors,
            source_ref=episode.source_ref,
        )

