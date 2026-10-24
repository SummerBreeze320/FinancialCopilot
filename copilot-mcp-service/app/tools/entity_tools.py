"""金融实体识别工具 (对接 copilot-ner-service: 8800)"""
import httpx
from loguru import logger
from app.config import settings


async def recognize_entities_tool(text: str) -> dict:
    """
    对输入文本进行金融实体识别（包含基金代码、基金名称、基金经理、股票等实体解析）。
    
    :param text: 待进行金融实体抽取的原始文本或用户问题
    :return: 抽取的实体列表与结构化元数据
    """
    url = f"{settings.ner_service_url}/v1/entities/recognize"
    try:
        async with httpx.AsyncClient(timeout=settings.request_timeout) as client:
            resp = await client.post(url, json={"text": text})
            if resp.status_code == 200:
                return resp.json()
            logger.warning(f"[FastMCP] NER 服务返回异常状态码: {resp.status_code}")
            return {
                "status": "FALLBACK",
                "message": f"NER service returned {resp.status_code}",
                "entities": []
            }
    except Exception as ex:
        logger.error(f"[FastMCP] 远程实体识别异常: {ex}")
        return {
            "status": "ERROR",
            "message": f"NER service unreachable: {str(ex)}",
            "entities": []
        }
