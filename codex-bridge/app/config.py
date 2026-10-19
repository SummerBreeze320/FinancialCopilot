from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="CODEX_BRIDGE_", extra="ignore")

    host: str = "127.0.0.1"
    port: int = 9090
    backend: str = "agy"
    agy_command: str = "agy"
    codex_command: str = "codex"
    timeout_seconds: float = 180.0
    max_concurrency: int = 1
