from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """基金筛选服务的运行配置。"""

    model_config = SettingsConfigDict(env_prefix="FUND_SCREENING_", extra="ignore")

    host: str = "0.0.0.0"
    port: int = 8810
    metrics_catalog_path: str = ""
    sectors_catalog_path: str = ""
    default_sector_id: str = "1000019220000000"
    group_limit_default: int = 100
    group_limit_max: int = 1000
    entity_catalog_path: str = ""
    llm_base_url: str = "http://127.0.0.1:8000/v1"
    llm_api_key: str = "local-codex"
    llm_model: str = "gpt-5.3-codex"
    llm_reasoning_effort: str = "low"
    embedding_base_url: str = "http://127.0.0.1:11434/v1"
    embedding_api_key: str = "local"
    embedding_model: str = "qwen3-embedding:0.6b"
    milvus_uri: str = "http://127.0.0.1:19530"
    milvus_token: str = ""
    metric_collection: str = "fund_metrics"
    scope_collection: str = "fund_scopes"
    pipeline_mode: str = "rag"
