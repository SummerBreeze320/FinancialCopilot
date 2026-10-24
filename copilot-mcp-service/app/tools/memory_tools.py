"""长期记忆事实检索工具 (对接 copilot-memory-service: 8700)"""
from typing import Optional
import httpx
from loguru import logger
from app.config import settings


async def search_memory_tool(
    query: str,
    user_id: Optional[str] = None,
    max_results: int = 5
) -> dict:
    """
    按作用域检索用户的长期记忆事实（如投资风格偏好、关注行业、历史约束事实）。
    
    :param query: 记忆检索查询词
    :param user_id: 用户唯一ID
    :param max_results: 返回的最大记忆记录条数
    :return: 匹配到的记忆事实清单
    """
    url = f"{settings.memory_service_url}/v1/memory/search"
    headers = {"X-Copilot-Service-Token": settings.copilot_service_token}
    payload = {
        "query": query,
        "user_id": user_id,
        "max_results": max_results
    }

    try:
        async with httpx.AsyncClient(timeout=settings.request_timeout) as client:
            resp = await client.post(url, headers=headers, json=payload)
            if resp.status_code == 200:
                return resp.json()
            logger.warning(f"[FastMCP] 记忆服务返回异常状态码: {resp.status_code}")
            return {
                "status": "FALLBACK",
                "message": f"Memory service returned {resp.status_code}",
                "results": []
            }
    except Exception as ex:
        logger.error(f"[FastMCP] 远程记忆检索异常: {ex}")
        return {
            "status": "ERROR",
            "message": f"Memory service unreachable: {str(ex)}",
            "results": []
        }
