"""记忆服务配置 — 从环境变量 / .env 读取"""

import os
from dataclasses import dataclass, field
from dotenv import load_dotenv

load_dotenv()


@dataclass(frozen=True)
class Neo4jConfig:
    uri: str = os.getenv("NEO4J_URI", "bolt://localhost:7687")
    user: str = os.getenv("NEO4J_USER", "neo4j")
    password: str = os.getenv("NEO4J_PASSWORD", "financial_copilot")


@dataclass(frozen=True)
class RedisConfig:
    host: str = os.getenv("REDIS_HOST", "localhost")
    port: int = int(os.getenv("REDIS_PORT", "6379"))
    password: str = os.getenv("REDIS_PASSWORD", "123456")
    db: int = int(os.getenv("REDIS_DB", "0"))


@dataclass(frozen=True)
class LlmConfig:
    api_key: str = os.getenv("LLM_API_KEY", "sk-placeholder")
    base_url: str = os.getenv("LLM_BASE_URL", "https://api.deepseek.com/v1")
    model: str = os.getenv("LLM_MODEL", "deepseek-chat")
    small_model: str = os.getenv("LLM_SMALL_MODEL", "deepseek-chat")


@dataclass(frozen=True)
class EmbeddingConfig:
    base_url: str = os.getenv("EMBEDDING_BASE_URL", "http://127.0.0.1:11434/v1")
    model: str = os.getenv("EMBEDDING_MODEL", "qwen3-embedding:0.6b")
    dim: int = int(os.getenv("EMBEDDING_DIM", "1024"))


@dataclass(frozen=True)
class ServerConfig:
    host: str = os.getenv("HOST", "0.0.0.0")
    port: int = int(os.getenv("PORT", "8700"))


@dataclass(frozen=True)
class ShortTermConfig:
    ttl_minutes: int = int(os.getenv("SHORT_TERM_TTL_MINUTES", "60"))
    max_messages: int = int(os.getenv("SHORT_TERM_MAX_MESSAGES", "50"))


@dataclass(frozen=True)
class GraphitiConfig:
    semaphore_limit: int = int(os.getenv("SEMAPHORE_LIMIT", "10"))


@dataclass(frozen=True)
class Settings:
    neo4j: Neo4jConfig = field(default_factory=Neo4jConfig)
    redis: RedisConfig = field(default_factory=RedisConfig)
    llm: LlmConfig = field(default_factory=LlmConfig)
    embedding: EmbeddingConfig = field(default_factory=EmbeddingConfig)
    server: ServerConfig = field(default_factory=ServerConfig)
    short_term: ShortTermConfig = field(default_factory=ShortTermConfig)
    graphiti: GraphitiConfig = field(default_factory=GraphitiConfig)


settings = Settings()
