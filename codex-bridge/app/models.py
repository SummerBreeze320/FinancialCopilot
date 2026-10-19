from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class ChatMessage(BaseModel):
    model_config = ConfigDict(extra="ignore")
    role: Literal["system", "user", "assistant"]
    content: str


class ResponseFormat(BaseModel):
    model_config = ConfigDict(extra="allow")
    type: str = "text"


class ChatCompletionRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")
    model: str = "gpt-5.6-terra"
    messages: list[ChatMessage] = Field(min_length=1)
    reasoning_effort: Literal["low", "medium", "high", "xhigh"] = "low"
    response_format: ResponseFormat | None = None


