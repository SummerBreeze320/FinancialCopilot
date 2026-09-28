from __future__ import annotations

from typing import Any, Literal
from pydantic import BaseModel, ConfigDict, Field


class ParameterOption(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    label: str
    value: Any
    aliases: list[str] = Field(default_factory=list)


class PrecisionConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    total_digits: int | None = Field(default=None, alias="totalDigits")
    scale: int | None = None
    rounding_mode: str | None = Field(default=None, alias="roundingMode")


class AcceptedInputUnit(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    unit: str
    multiplier: float | int


class ResultContract(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    value_type: str = Field(alias="valueType")
    precision: PrecisionConfig | None = None
    display_scale: int | None = Field(default=None, alias="displayScale")
    unit: str | None = None
    supported_operators: list[str] = Field(
        default_factory=lambda: [">", ">=", "<", "<=", "=", "!="],
        alias="supportedOperators",
    )
    storage_convention: str | None = Field(default=None, alias="storageConvention")
    accepted_input_units: list[AcceptedInputUnit] = Field(
        default_factory=list,
        alias="acceptedInputUnits",
    )
    options: list[ParameterOption] = Field(default_factory=list)


class MetricParameterDefinition(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    position: int = 0
    key: str
    value_type: Literal[
        "STRING",
        "NUMBER",
        "INTEGER",
        "DECIMAL",
        "BOOLEAN",
        "DATE",
        "FUND_CODE",
        "INDEX_CODE",
        "SECTOR_CODE",
        "ENUM",
    ] = Field(alias="valueType")
    semantic_type: str | None = Field(default=None, alias="semanticType")
    format: str | None = None
    pair_with: str | None = Field(default=None, alias="pairWith")
    required: bool = False
    default_value: Any | None = Field(default=None, alias="defaultValue")
    description: str | None = None
    options: list[ParameterOption] = Field(default_factory=list)


class MetricContract(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    mnemonic: str
    parameters: list[MetricParameterDefinition] = Field(default_factory=list)
    result: ResultContract | None = None
    parameter_schema_version: int = Field(default=1, alias="parameter_schema_version")
    contract_status: str = Field(default="INFERRED", alias="contract_status")


class MetricParameterValue(BaseModel):
    key: str
    value: Any
