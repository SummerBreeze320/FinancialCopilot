"""Financial Copilot FastMCP Service 配置模块"""
from pydantic_settings import BaseSettings, SettingsConfigDict


class McpServerSettings(BaseSettings):
    """MCP 服务全局配置"""
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )

    host: str = "127.0.0.1"
    port: int = 8085
    server_name: str = "financial-copilot-tools"
    server_version: str = "1.0.0"

    # 下游依赖微服务
    ner_service_url: str = "http://127.0.0.1:8800"
    memory_service_url: str = "http://127.0.0.1:8700"
    copilot_service_token: str = "test-token"

    # 默认超时时间（秒）
    request_timeout: float = 15.0


settings = McpServerSettings()
