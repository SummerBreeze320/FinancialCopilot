from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    entity_catalog_path: str = "data/entity.json"
    entity_admin_token: str = "change-me"
    database_url: str = "postgresql+asyncpg://postgres:123456@localhost:15432/financial_copilot"
    embedding_base_url: str = "http://127.0.0.1:11434/v1"
    embedding_model: str = "qwen3-embedding:0.6b"
    embedding_dim: int = 1024
    llm_api_key: str = "sk-placeholder"
    llm_base_url: str = "https://api.deepseek.com/v1"
    llm_model: str = "deepseek-chat"
    host: str = "0.0.0.0"
    port: int = 8800
    vector_top_k: int = Field(default=20, ge=1, le=100)
    vector_min_similarity: float = Field(default=0.68, ge=0, le=1)
    return_min_confidence: float = Field(default=0.55, ge=0, le=1)
    resolved_min_confidence: float = Field(default=0.86, ge=0, le=1)
    resolved_min_margin: float = Field(default=0.10, ge=0, le=1)
    ambiguous_max_candidates: int = Field(default=5, ge=2, le=100)
    max_mention_spans: int = Field(default=128, ge=1, le=1000)
    request_timeout_seconds: float = Field(default=15.0, gt=0)


@lru_cache
def get_settings() -> Settings:
    return Settings()
