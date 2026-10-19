"""内部服务间安全认证依赖"""

import os
from fastapi import Header, HTTPException, Security
from fastapi.security import APIKeyHeader

API_KEY_HEADER = APIKeyHeader(name="X-Copilot-Service-Token", auto_error=False)


def verify_service_token(token: str | None = Security(API_KEY_HEADER)) -> str | None:
    expected_token = (
        os.getenv("MEMORY_SERVICE_TOKEN")
        or os.getenv("COPILOT_SERVICE_TOKEN")
        or os.getenv("SERVICE_TOKEN")
    )
    if expected_token:
        if not token or token != expected_token:
            raise HTTPException(status_code=401, detail="Invalid or missing X-Copilot-Service-Token")
    return token
