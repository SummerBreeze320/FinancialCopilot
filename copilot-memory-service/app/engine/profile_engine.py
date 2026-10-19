"""用户画像引擎 — 从对话事实中提取用户画像维度，时序追加更新

画像维度 (金融场景定制):
  - risk_preference   风险偏好
  - expertise_level    专业水平
  - focus_sectors      关注板块
  - holding_style      持仓风格
  - fund_preference    基金偏好
  - decision_style     决策风格

画像不是覆盖更新，而是时序追加：旧值标记 invalid_at，新值 valid_at=now。
"""

import json
import logging
from datetime import datetime, timezone

from app.core.graphiti_init import get_graphiti
from app.config import settings
from app.models.schemas import (
    ProfileDimension,
    ProfileEntry,
    UserProfile,
    ProfileDiffEntry,
    ProfileDiffResponse,
)

logger = logging.getLogger(__name__)

PROFILE_DIMENSIONS = [d.value for d in ProfileDimension]

CLASSIFY_PROMPT = """你是一个金融用户画像分析器。从以下对话内容中提取用户的画像信息。

画像维度:
1. risk_preference   - 风险偏好 (如: 保守/稳健/进取)
2. expertise_level   - 专业水平 (如: 入门/中级/专家)
3. focus_sectors     - 关注板块 (如: 消费/新能源/半导体)
4. holding_style     - 持仓风格 (如: 长期持有/频繁轮动)
5. fund_preference   - 基金偏好 (如: 主动型/指数型/FOF)
6. decision_style    - 决策风格 (如: 数据驱动/跟风/独立判断)

只返回 JSON，不要其他文字。格式:
{
  "dimensions": [
    {"dimension": "risk_preference", "value": "稳健", "confidence": 0.8},
    ...
  ]
}

如果某维度无法从对话中推断，不要包含该维度。

对话内容:
{dialogue}
"""


class ProfileEngine:
    async def extract_and_update(
        self,
        user_id: str,
        dialogue: str,
        llm_chat_fn=None,
    ) -> dict:
        """从对话内容中提取画像维度并写入 Graphiti"""
        prompt = CLASSIFY_PROMPT.replace("{dialogue}", dialogue[:4000])

        if llm_chat_fn:
            raw = await llm_chat_fn(prompt)
        else:
            raw = await self._llm_chat(prompt)

        try:
            result = json.loads(raw)
            dimensions = result.get("dimensions", [])
        except (json.JSONDecodeError, TypeError) as e:
            logger.warning("Profile extraction parse failed: %s", e)
            return {"extracted": 0, "updated": 0}

        updated = 0
        for dim in dimensions:
            dim_name = dim.get("dimension", "")
            if dim_name not in PROFILE_DIMENSIONS:
                continue

            value = dim.get("value", "")
            confidence = float(dim.get("confidence", 0.5))

            if confidence < 0.3:
                continue

            await self._write_profile_fact(user_id, dim_name, value, confidence)
            updated += 1

        logger.info("Profile updated for user %s: %d dimensions", user_id, updated)
        return {"extracted": len(dimensions), "updated": updated}

    async def get_profile(self, user_id: str) -> UserProfile:
        """获取当前用户画像（只返回当前有效的事实）"""
        g = get_graphiti()
        results = await g.search(
            f"用户 {user_id} 的画像偏好",
            max_results=30,
        )

        dimensions = {}
        for r in results:
            fact_text = r.fact
            for dim_name in PROFILE_DIMENSIONS:
                if dim_name in fact_text.lower() or dim_name in fact_text:
                    invalid = getattr(r, "invalid_at", None)
                    if invalid is not None:
                        continue
                    dimensions[dim_name] = ProfileEntry(
                        dimension=ProfileDimension(dim_name),
                        value=fact_text,
                        confidence=0.8,
                        updated_at=getattr(r, "valid_at", None) or datetime.now(timezone.utc),
                    )
                    break

        return UserProfile(
            user_id=user_id,
            dimensions=dimensions,
            last_active=datetime.now(timezone.utc),
        )

    async def get_profile_diff(
        self, user_id: str, since: datetime | None = None
    ) -> ProfileDiffResponse:
        """获取用户画像变化历史（包含已失效的旧值）"""
        g = get_graphiti()
        results = await g.search(
            f"用户 {user_id} 的画像偏好变化",
            max_results=50,
        )

        diffs = []
        for r in results:
            fact_text = r.fact
            for dim_name in PROFILE_DIMENSIONS:
                if dim_name in fact_text.lower() or dim_name in fact_text:
                    valid = getattr(r, "valid_at", None)
                    invalid = getattr(r, "invalid_at", None)
                    if invalid is not None and (since is None or invalid > since):
                        diffs.append(ProfileDiffEntry(
                            dimension=ProfileDimension(dim_name),
                            old_value=fact_text,
                            new_value="(已变更)",
                            changed_at=invalid,
                        ))
                    break

        return ProfileDiffResponse(user_id=user_id, diffs=diffs)

    async def _write_profile_fact(
        self,
        user_id: str,
        dimension: str,
        value: str,
        confidence: float,
    ):
        """将画像事实写入 Graphiti，旧的同类事实自动失效"""
        from graphiti_core.nodes import EpisodeType

        g = get_graphiti()
        await g.add_episode(
            name=f"profile:{user_id}:{dimension}:{datetime.now(timezone.utc).isoformat()}",
            episode_body=f"用户 {user_id} 的 {dimension} 是 {value} (置信度: {confidence})",
            source=EpisodeType.text,
            source_description=f"user profile update: {dimension}",
            reference_time=datetime.now(timezone.utc),
        )

    async def _llm_chat(self, prompt: str) -> str:
        """调用 LLM (DeepSeek via OpenAI-compatible API)"""
        import httpx

        cfg = settings.llm
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{cfg.base_url}/chat/completions",
                headers={"Authorization": f"Bearer {cfg.api_key}"},
                json={
                    "model": cfg.model,
                    "messages": [{"role": "user", "content": prompt}],
                    "temperature": 0.1,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"]


profile_engine = ProfileEngine()
