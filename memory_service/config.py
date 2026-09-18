from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import Field


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

    # Server
    host: str = Field(default="0.0.0.0", alias="HOST")
    port: int = Field(default=8002, alias="PORT")
    debug: bool = Field(default=False, alias="DEBUG")

    # MySQL
    mysql_host: str = Field(default="127.0.0.1", alias="MYSQL_HOST")
    mysql_port: int = Field(default=3306, alias="MYSQL_PORT")
    mysql_user: str = Field(default="copilot", alias="MYSQL_USER")
    mysql_password: str = Field(default="copilot_pass", alias="MYSQL_PASSWORD")
    mysql_database: str = Field(default="financial_copilot", alias="MYSQL_DATABASE")
    mysql_pool_size: int = Field(default=10, alias="MYSQL_POOL_SIZE")
    mysql_max_overflow: int = Field(default=20, alias="MYSQL_MAX_OVERFLOW")

    # SQLite for testing/fallback
    use_sqlite: bool = Field(default=False, alias="USE_SQLITE")
    sqlite_db_path: str = Field(default=":memory:", alias="SQLITE_DB_PATH")

    @property
    def mysql_async_url(self) -> str:
        if self.use_sqlite:
            return f"sqlite+aiosqlite:///{self.sqlite_db_path}"
        return (
            f"mysql+asyncmy://{self.mysql_user}:{self.mysql_password}@"
            f"{self.mysql_host}:{self.mysql_port}/{self.mysql_database}?charset=utf8mb4"
        )

    # Milvus
    milvus_host: str = Field(default="127.0.0.1", alias="MILVUS_HOST")
    milvus_port: int = Field(default=19530, alias="MILVUS_PORT")
    milvus_user: str = Field(default="", alias="MILVUS_USER")
    milvus_password: str = Field(default="", alias="MILVUS_PASSWORD")
    milvus_collection: str = Field(default="ltm_memory_vector", alias="MILVUS_COLLECTION")
    milvus_dim: int = Field(default=1536, alias="MILVUS_DIM")

    # LLM
    llm_base_url: str = Field(default="https://api.deepseek.com/v1", alias="LLM_BASE_URL")
    llm_api_key: str = Field(default="", alias="LLM_API_KEY")
    llm_model: str = Field(default="deepseek-chat", alias="LLM_MODEL")

    # Embedding
    embedding_base_url: str = Field(default="https://api.openai.com/v1", alias="EMBEDDING_BASE_URL")
    embedding_api_key: str = Field(default="", alias="EMBEDDING_API_KEY")
    embedding_model: str = Field(default="text-embedding-3-small", alias="EMBEDDING_MODEL")
    embedding_dim: int = Field(default=1536, alias="EMBEDDING_DIM")

    # Online Budget
    default_token_budget: int = Field(default=500, alias="DEFAULT_TOKEN_BUDGET")


settings = Settings()
