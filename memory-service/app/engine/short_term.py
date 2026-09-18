"""短期记忆引擎 — Redis 快速缓存 + Graphiti Episode 持久化

修复 Java 端 ShortTermMemoryService 的问题:
- replaceContext() 覆盖 → 改为 append 追加模式
- Token 估算粗糙 → 按 max_messages 轮次控制
- TTL 硬编码 → 可配置
- 与长期记忆衔接断裂 → 每次写入同步写 Graphiti Episode
"""

import json
import logging
from datetime import datetime, timezone

import redis.asyncio as aioredis
from graphiti_core.nodes import EpisodeType

from app.config import settings
from app.core.graphiti_init import get_graphiti
from app.models.schemas import MessageRole

logger = logging.getLogger(__name__)


class ShortTermMemoryEngine:
    def __init__(self):
        self._redis: aioredis.Redis | None = None
        self._ttl = settings.short_term.ttl_minutes * 60
        self._max_messages = settings.short_term.max_messages

    async def _get_redis(self) -> aioredis.Redis:
        if self._redis is None:
            cfg = settings.redis
            self._redis = aioredis.Redis(
                host=cfg.host,
                port=cfg.port,
                password=cfg.password,
                db=cfg.db,
                decode_responses=True,
            )
        return self._redis

    @staticmethod
    def _key(session_id: str) -> str:
        return f"memory:shortterm:{session_id}"

    async def add_message(
        self,
        session_id: str,
        user_id: str,
        role: MessageRole,
        content: str,
        metadata: dict | None = None,
    ) -> dict:
        """追加一条消息到短期记忆，同步写入 Graphiti Episode"""
        msg = {
            "role": role.value,
            "content": content,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "user_id": user_id,
            "metadata": metadata or {},
        }
        redis = await self._get_redis()
        key = self._key(session_id)

        await redis.rpush(key, json.dumps(msg, ensure_ascii=False))
        await redis.expire(key, self._ttl)

        count = await redis.llen(key)
        if count > self._max_messages:
            await redis.ltrim(key, -self._max_messages, -1)
            count = self._max_messages

        try:
            g = get_graphiti()
            episode_body = f"[{role.value.upper()}] {content}"
            await g.add_episode(
                name=f"session:{session_id}:{role.value}:{datetime.now(timezone.utc).isoformat()}",
                episode_body=episode_body,
                source=EpisodeType.message,
                source_description=f"FinancialCopilot session {session_id}",
                reference_time=datetime.now(timezone.utc),
            )
        except Exception as e:
            logger.warning("Graphiti episode write failed (non-blocking): %s", e)

        return {"session_id": session_id, "message_count": count}

    async def get_context(
        self, session_id: str, max_messages: int | None = None
    ) -> list[dict]:
        """读取短期记忆上下文"""
        redis = await self._get_redis()
        key = self._key(session_id)
        limit = max_messages or self._max_messages
        raw_list = await redis.lrange(key, -limit, -1)
        return [json.loads(raw) for raw in raw_list]

    async def get_context_string(
        self, session_id: str, max_messages: int | None = None
    ) -> str:
        """读取短期记忆并拼接为 LLM 上下文字符串"""
        messages = await self.get_context(session_id, max_messages)
        lines = []
        for msg in messages:
            prefix = msg["role"].upper()
            lines.append(f"{prefix}: {msg['content']}")
        return "\n".join(lines)

    async def clear(self, session_id: str) -> bool:
        redis = await self._get_redis()
        key = self._key(session_id)
        deleted = await redis.delete(key)
        return deleted > 0

    async def close(self):
        if self._redis:
            await self._redis.close()
            self._redis = None


short_term_engine = ShortTermMemoryEngine()
