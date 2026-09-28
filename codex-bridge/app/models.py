from typing import Literal

from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    role: Literal["system", "user", "assistant"]
    content: str


class ResponseFormat(BaseModel):
    type: Literal["json_object"] = "json_object"


class ChatCompletionRequest(BaseModel):
    model: str = "gpt-5.6-terra"
    messages: list[ChatMessage] = Field(min_length=1)
    reasoning_effort: Literal["low", "medium", "high", "xhigh"] = "low"
    response_format: ResponseFormat = ResponseFormat()
