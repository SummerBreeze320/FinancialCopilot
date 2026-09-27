from __future__ import annotations

import asyncio
from dataclasses import dataclass
from pathlib import Path
import uuid

from app.catalog.loader import CatalogLoader


class RebuildAlreadyRunning(RuntimeError):
    pass


@dataclass(slots=True)
class IndexJob:
    job_id: str
    status: str
    processed: int = 0
    total: int = 0
    version_id: str | None = None
    error: str | None = None


class IndexingService:
    def __init__(self, repository, loader: CatalogLoader, embedding, batch_size: int = 128) -> None:
        self._repository = repository
        self._loader = loader
        self._embedding = embedding
        self._batch_size = batch_size
        self._guard = asyncio.Lock()
        self._jobs: dict[str, IndexJob] = {}
        self._tasks: dict[str, asyncio.Task] = {}
        self._running_job_id: str | None = None

    async def start_rebuild(self, path: str | Path, force: bool = False) -> IndexJob:
        async with self._guard:
            if self._running_job_id is not None:
                task = self._tasks[self._running_job_id]
                if not task.done():
                    raise RebuildAlreadyRunning("catalog rebuild already running")
            job = IndexJob(job_id=str(uuid.uuid4()), status="queued")
            self._jobs[job.job_id] = job
            self._running_job_id = job.job_id
            self._tasks[job.job_id] = asyncio.create_task(self._run(job, Path(path), force))
            return job

    def get_job(self, job_id: str) -> IndexJob | None:
        return self._jobs.get(job_id)

    async def wait(self, job_id: str) -> IndexJob:
        task = self._tasks.get(job_id)
        if task is None:
            raise KeyError(job_id)
        await task
        return self._jobs[job_id]

    async def _run(self, job: IndexJob, path: Path, force: bool) -> None:
        version_id = None
        try:
            job.status = "running"
            document = self._loader.load(path)
            job.total = len(document.entities)
            existing = await self._repository.find_version_by_sha(document.sha256)
            if existing is not None and not force:
                job.version_id = str(existing.version_id)
                job.processed = existing.entity_count if hasattr(existing, "entity_count") else job.total
                job.status = "completed"
                return
            version_id = await self._repository.create_version(
                document.version, document.source, document.sha256 if not force else f"{document.sha256}:{uuid.uuid4()}"
            )
            job.version_id = str(version_id)
            for start in range(0, len(document.entities), self._batch_size):
                records = list(document.entities[start:start + self._batch_size])
                embeddings = await self._embedding.embed([record.search_text for record in records])
                await self._repository.insert_batch(version_id, list(zip(records, embeddings, strict=True)))
                job.processed += len(records)
            await self._repository.activate_version(version_id)
            job.status = "completed"
        except Exception as exc:
            if version_id is not None:
                await self._repository.fail_version(version_id)
            job.status = "failed"
            job.error = str(exc)
        finally:
            async with self._guard:
                if self._running_job_id == job.job_id:
                    self._running_job_id = None
