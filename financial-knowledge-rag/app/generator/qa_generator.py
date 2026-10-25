"""基于本地 Codex-Bridge LLM 的流式问答与引用溯源生成器。"""

import json
import logging
from typing import Any, AsyncGenerator, Dict, List, Optional
import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class QaGenerator:
    """问答生成器，调用本地独立运行的 codex-bridge (Gemini Flash) 并输出引用切片来源。"""

    def __init__(
        self,
        base_url: Optional[str] = None,
        api_key: Optional[str] = None,
        model: Optional[str] = None,
    ) -> None:
        """初始化问答生成器。

        :param base_url: OpenAI 兼容接口，默认 http://127.0.0.1:9090/v1
        :param api_key: 授权 Key，默认 local-agy
        :param model: 模型名称，默认 gemini-3.8-flash-low
        """
        self.base_url = (base_url or settings.llm_base_url).rstrip("/")
        self.api_key = api_key or settings.llm_api_key
        self.model = model or settings.llm_model

    async def generate(
        self,
        query: str,
        chunks: List[Dict[str, Any]],
    ) -> str:
        """非流式调用本地 Codex-Bridge LLM 生成包含引用角标的完整回答。"""
        context_parts: List[str] = []
        for idx, chunk in enumerate(chunks, start=1):
            title = chunk.get("doc_title") or chunk.get("document_title") or "未知文档"
            page = chunk.get("page_number", "-")
            inst = chunk.get("institution") or "公开渠道"
            content = chunk.get("content", "")
            context_parts.append(
                f"[{idx}] 《{title}》 (机构: {inst}, 页码: {page}):\n{content}"
            )
        context_str = "\n\n".join(context_parts)

        system_prompt = (
            "你是一个严谨客观的金融投研高级分析师。\n"
            "请严格且仅依据下面给出的【参考知识切片】回答用户的金融问题。\n"
            "严禁凭空捏造任何财务数据、增长率、指标或事实。\n"
            "在回答中引用具体论点或数字时，请在句子末尾标注对应的引用角标，如 [1]、[2]。\n"
            "若参考知识切片中未包含答案所需的信息，请明确告知'根据现有参考文档暂无法确认'。\n\n"
            f"【参考知识切片】\n{context_str}"
        )

        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": query},
            ],
            "temperature": 0.1,
            "stream": False,
        }

        url = f"{self.base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        try:
            async with httpx.AsyncClient(timeout=60.0, trust_env=False) as client:
                resp = await client.post(url, json=payload, headers=headers)
                resp.raise_for_status()
                res_json = resp.json()
                return res_json.get("choices", [{}])[0].get("message", {}).get("content", "").strip()
        except Exception as e:
            logger.error("Codex-Bridge LLM 非流式生成异常: %s", e)
            return f"【服务提示】本地模型调用失败: {e}"

    async def generate_stream(
        self,
        query: str,
        chunks: List[Dict[str, Any]],
    ) -> AsyncGenerator[str, None]:
        """流式生成包含引用溯源的回答。

        :param query: 用户金融问题
        :param chunks: 检索并重排后的 Top-K 结构化知识片段
        :return: SSE 格式的数据流生成器
        """
        # 构建上下文提示词
        context_parts: List[str] = []
        for idx, chunk in enumerate(chunks, start=1):
            title = chunk.get("doc_title") or chunk.get("document_title") or "未知文档"
            page = chunk.get("page_number", "-")
            inst = chunk.get("institution") or "公开渠道"
            content = chunk.get("content", "")
            context_parts.append(
                f"[{idx}] 《{title}》 (机构: {inst}, 页码: {page}):\n{content}"
            )
        context_str = "\n\n".join(context_parts)

        system_prompt = (
            "你是一个严谨客观的金融投研高级分析师。\n"
            "请严格且仅依据下面给出的【参考知识切片】回答用户的金融问题。\n"
            "严禁凭空捏造任何财务数据、增长率、指标或事实。\n"
            "在回答中引用具体论点或数字时，请在句子末尾标注对应的引用角标，如 [1]、[2]。\n"
            "若参考知识切片中未包含答案所需的信息，请明确告知'根据现有参考文档暂无法确认'。\n\n"
            f"【参考知识切片】\n{context_str}"
        )

        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": query},
            ],
            "temperature": 0.1,
            "stream": True,
        }

        url = f"{self.base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        try:
            async with httpx.AsyncClient(timeout=60.0, trust_env=False) as client:
                async with client.stream("POST", url, json=payload, headers=headers) as resp:
                    resp.raise_for_status()
                    async for line in resp.aiter_lines():
                        if not line or not line.startswith("data: "):
                            continue
                        data_str = line[6:].strip()
                        if data_str == "[DONE]":
                            break
                        try:
                            item = json.loads(data_str)
                            delta = item.get("choices", [{}])[0].get("delta", {})
                            content = delta.get("content", "")
                            if content:
                                yield f"data: {json.dumps({'content': content}, ensure_ascii=False)}\n\n"
                        except json.JSONDecodeError:
                            continue
        except Exception as e:
            logger.error("Codex-Bridge LLM 流式问答生成异常: %s", e)
            yield f"data: {json.dumps({'content': f'【服务提示】本地模型生成暂不可用: {e}'}, ensure_ascii=False)}\n\n"

        # 末尾输出溯源来源清单 (Citations)
        if chunks:
            citations_header = "\n\n### 📑 参考溯源清单：\n"
            yield f"data: {json.dumps({'content': citations_header}, ensure_ascii=False)}\n\n"
            for idx, chunk in enumerate(chunks, start=1):
                title = chunk.get("doc_title") or chunk.get("document_title") or "未知文档"
                page = chunk.get("page_number", "-")
                score = chunk.get("score", 0.0)
                inst = chunk.get("institution") or "公开渠道"
                cite_line = f"[{idx}] 《{title}》 (机构: {inst}, 页码: {page}, 置信度: {score:.2f})\n"
                yield f"data: {json.dumps({'content': cite_line}, ensure_ascii=False)}\n\n"

        yield "data: [DONE]\n\n"
