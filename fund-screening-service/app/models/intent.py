from enum import Enum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class IntentType(str, Enum):
    CREATE = "CREATE"
    EDIT = "EDIT"


class IntentDecision(BaseModel):
    """基金筛选一级意图的可审计判定结果。"""
    model_config = ConfigDict(populate_by_name=True)

    intent: Literal["CREATE", "EDIT"]
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: list[str] = Field(default_factory=list)
    source: Literal["RULE", "CONTEXT_MODEL", "COMPLEX_MODEL"]
    create_probability: float | None = Field(default=None, ge=0.0, le=1.0)
    edit_probability: float | None = Field(default=None, ge=0.0, le=1.0)
    reason_codes: list[str] = Field(default_factory=list)


class IntentModelOutput(BaseModel):
    """模型层只允许输出一级意图与其判定证据。"""
    model_config = ConfigDict(populate_by_name=True)

    intent: Literal["CREATE", "EDIT"]
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: list[str] = Field(default_factory=list)
    create_probability: float = Field(ge=0.0, le=1.0)
    edit_probability: float | None = Field(default=None, ge=0.0, le=1.0)
