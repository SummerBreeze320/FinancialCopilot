"""基于本地 Codex-Bridge VLM 的金融图表与流程图多模态转译器。

将复杂流程图、股权架构穿透图与趋势图解构为结构化文本和 Mermaid 语法。
"""

import base64
import logging
from typing import Optional
import httpx

logger = logging.getLogger(__name__)


VLM_SYSTEM_PROMPT = """你是一个专业的金融投研多模态图表解析专家。
请仔细观察所提供的金融文档插图（可能是交易结构流程图、股权穿透图、业务流程图、走势图或附注图表）：
1. 若为流程图/架构图：请明确梳理出核心参与主体、持股比例/出资金额、箭头指向与交易顺序，并输出易于渲染的 Markdown 及 Mermaid 流程图代码块（```mermaid ... ```）；
2. 若为趋势图/柱状图：请提取横纵坐标、极值点及核心增长/下滑趋势；
3. 输出请保持客观、严密，直接输出分析结果，切勿附带客套废话。"""


class VlmAnalyzer:
    """多模态图表分析器。"""

    def __init__(
        self,
        base_url: str = "http://127.0.0.1:9090/v1",
        api_key: str = "local-agy",
        model: str = "gemini-3.8-flash-low",
        timeout: float = 30.0
    ):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    async def analyze_diagram(self, image_bytes: bytes, diagram_hint: str = "") -> str:
        """调用本地 Codex-Bridge 多模态端点转译图表。"""
        if not image_bytes or len(image_bytes) < 5:
            return ""

        b64_img = base64.b64encode(image_bytes).decode("utf-8")
        data_uri = f"data:image/png;base64,{b64_img}"

        user_prompt = f"请解析这张金融文档图表（提示类型: {diagram_hint or '流程图/股权图'}）。"

        payload = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": VLM_SYSTEM_PROMPT
                },
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": user_prompt},
                        {"type": "image_url", "image_url": {"url": data_uri}}
                    ]
                }
            ],
            "temperature": 0.1,
            "max_tokens": 1500
        }

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }

        try:
            async with httpx.AsyncClient(timeout=self.timeout, trust_env=False) as client:
                resp = await client.post(f"{self.base_url}/chat/completions", json=payload, headers=headers)
                if resp.status_code == 200:
                    data = resp.json()
                    content = data["choices"][0]["message"]["content"]
                    return content.strip()
                else:
                    logger.warning(f"[VLM] 多模态接口返回异常状态码: {resp.status_code}, 内容: {resp.text}")
                    return f"[流程图解析降级: 接口返回 {resp.status_code}]"
        except Exception as e:
            logger.warning(f"[VLM] 多模态调用超时或异常，执行降级: {e}")
            return f"[流程图解析降级: {e}]"
