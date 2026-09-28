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
