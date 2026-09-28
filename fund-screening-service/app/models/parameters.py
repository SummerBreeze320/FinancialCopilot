from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field


class ParameterOption(BaseModel):
    label: str
    value: str


class MetricParameterDefinition(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    key: str
    value_type: Literal[
        "STRING", "NUMBER", "INTEGER", "BOOLEAN", "DATE", "FUND_CODE", "INDEX_CODE", "SECTOR_CODE", "ENUM"
    ] = Field(alias="valueType")
    required: bool = False
    default_value: Any | None = Field(default=None, alias="defaultValue")
    options: list[ParameterOption] = Field(default_factory=list)


class MetricParameterValue(BaseModel):
    key: str
    value: Any
