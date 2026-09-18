from typing import Any
from pydantic import BaseModel, Field


class Procedure(BaseModel):
    """
    过程性经验模型：沉淀任务业务方法、恢复策略与成功模式
    不包含 DAG 节点 ID 或 ReAct 局部状态，具备跨运行时通用性
    """
    task_type: str = Field(description="任务类型，如 FUND_COMPARISON, MARKET_SCREENING, TOOL_RETRY")
    trigger_conditions: list[str] = Field(default_factory=list, description="触发场景/模式描述")
    preconditions: list[str] = Field(default_factory=list, description="前置必要约束（必须满足方可执行）")
    steps: list[str] = Field(default_factory=list, description="规范化推荐执行步骤")
    applicability: dict[str, Any] = Field(
        default_factory=dict,
        description="精确条件匹配规则（如 {'error_code': 'TIMEOUT', 'tool_is_idempotent': True}）"
    )
    validation_rules: list[str] = Field(default_factory=list, description="执行后结果正确性自检规则")
    failure_signatures: list[str] = Field(default_factory=list, description="匹配的失败特征/错误码签名")
    recovery_actions: list[str] = Field(default_factory=list, description="推荐的挽回/恢复动作")
