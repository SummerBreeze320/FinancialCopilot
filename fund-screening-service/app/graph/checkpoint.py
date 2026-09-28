from __future__ import annotations

import pickle
import uuid
from typing import Any, AsyncIterator, Iterator, Sequence

from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
    WRITES_IDX_MAP,
    get_checkpoint_id,
    get_checkpoint_metadata,
)
from langgraph.checkpoint.memory import MemorySaver


class RedisCheckpointSaver(BaseCheckpointSaver):
    """基于 Redis Hash 结构存储的 LangGraph 检查点持久化器。"""

    def __init__(self, client: Any = None, redis_url: str | None = None, key_prefix: str = "fs:checkpoint") -> None:
        super().__init__()
        if client is not None:
            self.client = client
        elif redis_url:
            import redis
            self.client = redis.Redis.from_url(redis_url, decode_responses=False)
        else:
            raise ValueError("Either client or redis_url must be provided")
        self.key_prefix = key_prefix

    @classmethod
    def from_conn_string(cls, conn_string: str) -> RedisCheckpointSaver:
        return cls(redis_url=conn_string)

    def _storage_key(self, thread_id: str, checkpoint_ns: str) -> str:
        return f"{self.key_prefix}:{thread_id}:{checkpoint_ns}"

    def _blobs_key(self, thread_id: str, checkpoint_ns: str) -> str:
        return f"{self.key_prefix}:blobs:{thread_id}:{checkpoint_ns}"

    def _writes_key(self, thread_id: str, checkpoint_ns: str, checkpoint_id: str) -> str:
        return f"{self.key_prefix}:writes:{thread_id}:{checkpoint_ns}:{checkpoint_id}"

    def _load_blobs(self, thread_id: str, checkpoint_ns: str, versions: ChannelVersions) -> dict[str, Any]:
        result: dict[str, Any] = {}
        blobs_key = self._blobs_key(thread_id, checkpoint_ns)
        for k, ver in versions.items():
            field = f"{k}:{ver}"
            raw = self.client.hget(blobs_key, field)
            if raw is None:
                continue
            vv = pickle.loads(raw) if isinstance(raw, bytes) else raw
            if vv[0] == "empty":
                continue
            result[k] = self.serde.loads_typed(vv)
        return result

    def get_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        thread_id: str = config["configurable"]["thread_id"]
        checkpoint_ns: str = config["configurable"].get("checkpoint_ns", "")
        storage_key = self._storage_key(thread_id, checkpoint_ns)

        checkpoint_id = get_checkpoint_id(config)
        if not checkpoint_id:
            all_entries = self.client.hgetall(storage_key)
            if not all_entries:
                return None
            keys = [k.decode("utf-8") if isinstance(k, bytes) else str(k) for k in all_entries.keys()]
            checkpoint_id = max(keys)

        raw = self.client.hget(storage_key, checkpoint_id)
        if raw is None:
            return None

        saved = pickle.loads(raw) if isinstance(raw, bytes) else raw
        checkpoint, metadata, parent_checkpoint_id = saved
        checkpoint_: Checkpoint = self.serde.loads_typed(checkpoint)

        writes_key = self._writes_key(thread_id, checkpoint_ns, checkpoint_id)
        all_writes = self.client.hgetall(writes_key)
        writes = [pickle.loads(w) if isinstance(w, bytes) else w for w in all_writes.values()]

        return CheckpointTuple(
            config={
                "configurable": {
                    "thread_id": thread_id,
                    "checkpoint_ns": checkpoint_ns,
                    "checkpoint_id": checkpoint_id,
                }
            },
            checkpoint={
                **checkpoint_,
                "channel_values": self._load_blobs(
                    thread_id, checkpoint_ns, checkpoint_["channel_versions"]
                ),
            },
            metadata=self.serde.loads_typed(metadata),
            pending_writes=[
                (id, c, self.serde.loads_typed(v)) for id, c, v, _ in writes
            ],
            parent_config=(
                {
                    "configurable": {
                        "thread_id": thread_id,
                        "checkpoint_ns": checkpoint_ns,
                        "checkpoint_id": parent_checkpoint_id,
                    }
                }
                if parent_checkpoint_id
                else None
            ),
        )

    def put(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        c = checkpoint.copy()
        thread_id = config["configurable"]["thread_id"]
        checkpoint_ns = config["configurable"].get("checkpoint_ns", "")
        values: dict[str, Any] = c.pop("channel_values")

        blobs_key = self._blobs_key(thread_id, checkpoint_ns)
        for k, v in new_versions.items():
            field = f"{k}:{v}"
            blob_val = self.serde.dumps_typed(values[k]) if k in values else ("empty", b"")
            self.client.hset(blobs_key, field, pickle.dumps(blob_val))

        storage_key = self._storage_key(thread_id, checkpoint_ns)
        entry = (
            self.serde.dumps_typed(c),
            self.serde.dumps_typed(get_checkpoint_metadata(config, metadata)),
            config["configurable"].get("checkpoint_id"),
        )
        self.client.hset(storage_key, checkpoint["id"], pickle.dumps(entry))

        return {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": checkpoint_ns,
                "checkpoint_id": checkpoint["id"],
            }
        }

    def put_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        thread_id = config["configurable"]["thread_id"]
        checkpoint_ns = config["configurable"].get("checkpoint_ns", "")
        checkpoint_id = config["configurable"]["checkpoint_id"]
        writes_key = self._writes_key(thread_id, checkpoint_ns, checkpoint_id)

        for idx, (c, v) in enumerate(writes):
            inner_key = f"{task_id}:{WRITES_IDX_MAP.get(c, idx)}"
            val = (task_id, c, self.serde.dumps_typed(v), task_path)
            self.client.hset(writes_key, inner_key, pickle.dumps(val))

    def delete_thread(self, thread_id: str) -> None:
        self.client.delete(self._storage_key(thread_id, ""))
        self.client.delete(self._blobs_key(thread_id, ""))

    def list(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> Iterator[CheckpointTuple]:
        if not config:
            return iter([])
        t = self.get_tuple(config)
        return iter([t] if t else [])

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        return self.get_tuple(config)

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        return self.put(config, checkpoint, metadata, new_versions)

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        return self.put_writes(config, writes, task_id, task_path)

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        for item in self.list(config, filter=filter, before=before, limit=limit):
            yield item

    async def adelete_thread(self, thread_id: str) -> None:
        return self.delete_thread(thread_id)


def build_checkpointer(redis_url: str | None = None) -> BaseCheckpointSaver:
    if redis_url:
        try:
            saver = RedisCheckpointSaver.from_conn_string(redis_url)
            saver.client.ping()
            return saver
        except Exception:
            return MemorySaver()
    return MemorySaver()


def make_graph_config(conversation_id: str | None = None) -> dict[str, Any]:
    thread_id = conversation_id if conversation_id else str(uuid.uuid4())
    return {"configurable": {"thread_id": thread_id}}

