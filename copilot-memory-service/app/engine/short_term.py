"""短期记忆引擎 — Redis 快速缓存 + Graphiti Episode 持久化"""

import json
import logging
from datetime import datetime, timezone
from typing import Any

import redis.asyncio as aioredis
from graphiti_core.nodes import EpisodeType

from app.config import settings
from app.core.graphiti_init import get_graphiti
from app.models.schemas import MessageRole

logger = logging.getLogger(__name__)


def short_term_key(user_id: str, conversation_id: str) -> str:
    return f"memory:shortterm:user:{user_id}:conversation:{conversation_id}"


def session_owner_key(conversation_id: str) -> str:
    return f"memory:owner:conversation:{conversation_id}"


class ShortTermMemoryEngine:
    def __init__(self):
        self._redis: aioredis.Redis | None = None
        self._ttl = settings.short_term.ttl_minutes * 60
        self._max_messages = settings.short_term.max_messages
        self._in_memory_store: dict[str, list[dict]] = {}
        self._in_memory_owners: dict[str, str] = {}
        self._redis_last_attempt: float = 0.0
        self._redis_retry_cooldown: float = 15.0

    async def _get_redis(self) -> aioredis.Redis | None:
        if self._redis is not None:
            return self._redis
        import time
        now = time.time()
        if now - self._redis_last_attempt < self._redis_retry_cooldown:
            return None
        self._redis_last_attempt = now
        try:
            cfg = settings.redis
            client = aioredis.Redis(
                host=cfg.host,
                port=cfg.port,
                password=cfg.password,
                db=cfg.db,
                decode_responses=True,
                socket_connect_timeout=0.3,
                socket_timeout=0.3,
            )
            await client.ping()
            self._redis = client
        except Exception:
            self._redis = None
        return self._redis

    async def add_message(
        self,
        user_id_or_session: str | None = None,
        conversation_id_or_user: str | None = None,
        role: MessageRole | str = MessageRole.USER,
        content: str = "",
        metadata: dict | None = None,
        session_id: str | None = None,
        user_id: str | None = None,
    ) -> dict:
        """追加一条消息到短期记忆，支持 (user_id, conversation_id) 或 (session_id, user_id) 传参"""
        if user_id is not None and session_id is not None:
            actual_user_id = user_id
            actual_conversation_id = session_id
        elif user_id_or_session is not None and conversation_id_or_user is not None and not isinstance(conversation_id_or_user, MessageRole) and not (isinstance(conversation_id_or_user, str) and conversation_id_or_user in ("user", "assistant", "system")):
            actual_user_id = user_id_or_session
            actual_conversation_id = conversation_id_or_user
        else:
            # session_id, role, content passed positionally or with keywords
            actual_conversation_id = session_id or user_id_or_session or "default"
            actual_user_id = user_id or "default"
            if isinstance(conversation_id_or_user, (MessageRole, str)):
                role = conversation_id_or_user

        if ":" in actual_conversation_id and actual_user_id == "default":
            parts = actual_conversation_id.split(":", 1)
            actual_user_id = parts[0]
            actual_conversation_id = parts[1]

        role_str = role.value if hasattr(role, "value") else str(role)
        msg = {
            "role": role_str,
            "content": content,
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "user_id": actual_user_id,
            "metadata": metadata or {},
        }

        key = short_term_key(actual_user_id, actual_conversation_id)
        owner_key = session_owner_key(actual_conversation_id)

        redis = await self._get_redis()
        if redis is not None:
            try:
                await redis.set(owner_key, actual_user_id, ex=self._ttl)
                await redis.rpush(key, json.dumps(msg, ensure_ascii=False))
                await redis.expire(key, self._ttl)
                count = await redis.llen(key)
                if count > self._max_messages:
                    await redis.ltrim(key, -self._max_messages, -1)
                    count = self._max_messages
            except Exception:
                redis = None

        if redis is None:
            self._in_memory_owners[actual_conversation_id] = actual_user_id
            msgs = self._in_memory_store.setdefault(key, [])
            msgs.append(msg)
            if len(msgs) > self._max_messages:
                msgs = msgs[-self._max_messages:]
                self._in_memory_store[key] = msgs
            count = len(msgs)

        try:
            g = get_graphiti()
            episode_body = f"[{role_str.upper()}] {content}"
            await g.add_episode(
                name=f"session:{actual_conversation_id}:{role_str}:{datetime.now(timezone.utc).isoformat()}",
                episode_body=episode_body,
                source=EpisodeType.message,
                source_description=f"FinancialCopilot session {actual_conversation_id}",
                reference_time=datetime.now(timezone.utc),
                group_id=f"user:{actual_user_id}",
            )
        except Exception as e:
            logger.warning("Graphiti episode write failed (non-blocking): %s", e)

        # 触发词“记住”即刻写入长期记忆库
        if role_str == "user":
            try:
                from app.engine.explicit_memory import explicit_memory_engine
                from app.engine.long_term import long_term_engine
                if explicit_memory_engine.is_explicit_trigger(content):
                    await long_term_engine.add_explicit_memory(
                        user_id=actual_user_id,
                        content=content,
                        session_id=actual_conversation_id,
                    )
            except Exception as e:
                logger.warning("Automatic explicit memory trigger failed: %s", e)

        return {"session_id": actual_conversation_id, "user_id": actual_user_id, "message_count": count}

    async def get_context(
        self,
        session_id_or_user_id: str,
        conversation_id: str | None = None,
        max_messages: int | None = None,
    ) -> list[dict] | None:
        """读取指定用户的短期记忆上下文，隔离不同用户访问"""
        if conversation_id is not None:
            user_id = session_id_or_user_id
            conv_id = conversation_id
        else:
            conv_id = session_id_or_user_id
            user_id = "default"
            if ":" in conv_id:
                parts = conv_id.split(":", 1)
                user_id = parts[0]
                conv_id = parts[1]

        owner_key = session_owner_key(conv_id)
        key = short_term_key(user_id, conv_id)
        limit = max_messages or self._max_messages

        redis = await self._get_redis()
        owner = None
        if redis is not None:
            try:
                owner = await redis.get(owner_key)
            except Exception:
                redis = None
        if redis is None:
            owner = self._in_memory_owners.get(conv_id)

        if owner is not None and owner != user_id:
            # 越权访问：会话属于另一用户
            return None

        if redis is not None:
            try:
                raw_list = await redis.lrange(key, -limit, -1)
                msgs = [json.loads(raw) for raw in raw_list]
                if not msgs and owner is None:
                    return None
                return msgs
            except Exception:
                pass

        if conv_id not in self._in_memory_owners and key not in self._in_memory_store:
            return None
        return self._in_memory_store.get(key, [])[-limit:]

    async def clear(
        self,
        session_id_or_user_id: str,
        conversation_id: str | None = None,
    ) -> bool:
        """清空会话短期记忆"""
        if conversation_id is not None:
            user_id = session_id_or_user_id
            conv_id = conversation_id
        else:
            conv_id = session_id_or_user_id
            user_id = "default"
            if ":" in conv_id:
                parts = conv_id.split(":", 1)
                user_id = parts[0]
                conv_id = parts[1]

        owner_key = session_owner_key(conv_id)
        key = short_term_key(user_id, conv_id)

        redis = await self._get_redis()
        if redis is not None:
            try:
                owner = await redis.get(owner_key)
                if owner is not None and owner != user_id:
                    return False
                d1 = await redis.delete(key)
                await redis.delete(owner_key)
                return d1 > 0
            except Exception:
                pass

        if conv_id in self._in_memory_owners and self._in_memory_owners[conv_id] != user_id:
            return False
        had = key in self._in_memory_store or conv_id in self._in_memory_owners
        self._in_memory_store.pop(key, None)
        self._in_memory_owners.pop(conv_id, None)
        return had

    async def close(self):
        if self._redis:
            await self._redis.close()
            self._redis = None


short_term_engine = ShortTermMemoryEngine()
