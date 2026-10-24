"""金融知识库服务的全局配置管理。"""

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """金融知识库运行配置。"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

    # 基础网络与端口
    host: str = Field(default="0.0.0.0", validation_alias="RAG_HOST")
    port: int = Field(default=8820, validation_alias="RAG_PORT")

    # PostgreSQL + pgvector
    database_url: str = Field(
        default="postgresql://postgres:123456@127.0.0.1:15432/financial_copilot",
        validation_alias="DATABASE_URL"
    )

    # 本地 LLM & 多模态 VLM (遵循 Codex-Bridge 标准)
    llm_base_url: str = Field(
        default="http://127.0.0.1:9090/v1",
        validation_alias="OPENAI_BASE_URL"
    )
    llm_api_key: str = Field(
        default="local-agy",
        validation_alias="OPENAI_API_KEY"
    )
    llm_model: str = Field(
        default="gemini-3.8-flash-low",
        validation_alias="MODEL_NAME"
    )

    # 本地 Embedding 服务 (Ollama / Local)
    embedding_base_url: str = Field(
        default="http://127.0.0.1:11434/v1",
        validation_alias="EMBEDDING_BASE_URL"
    )
    embedding_api_key: str = Field(
        default="local",
        validation_alias="EMBEDDING_API_KEY"
    )
    embedding_model: str = Field(
        default="qwen3-embedding:0.6b",
        validation_alias="EMBEDDING_MODEL"
    )
    embedding_dim: int = Field(
        default=1024,
        validation_alias="EMBEDDING_DIM"
    )

    # 硬件加速与 OCR
    enable_gpu_ocr: bool = Field(default=True, validation_alias="ENABLE_GPU_OCR")
    gpu_device_id: int = Field(default=0, validation_alias="GPU_DEVICE_ID")

    # 神经重排器与检索漏斗参数
    reranker_type: str = Field(default="neural", validation_alias="RERANKER_TYPE")
    reranker_model_path: str = Field(
        default="BAAI/bge-reranker-large",
        validation_alias="RERANKER_MODEL_PATH"
    )
    reranker_device: str = Field(default="cuda", validation_alias="RERANKER_DEVICE")
    top_k_default: int = Field(default=5, validation_alias="TOP_K_DEFAULT")
    candidate_pool_size: int = Field(default=30, validation_alias="CANDIDATE_POOL_SIZE")
    rrf_k: int = Field(default=60, validation_alias="RRF_K")
    weight_vector: float = Field(default=0.6, validation_alias="WEIGHT_VECTOR")
    weight_bm25: float = Field(default=0.4, validation_alias="WEIGHT_BM25")
    score_threshold: float = Field(default=0.35, validation_alias="SCORE_THRESHOLD")


settings = Settings()
