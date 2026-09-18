import asyncio
import logging
from datetime import datetime, timezone
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from memory_service.domain.enums import JobStatus
from memory_service.infrastructure.mysql.models import LtmJobModel
from memory_service.infrastructure.mysql.memory_repository import MemoryRepository
from memory_service.engine.publication.memory_publisher import MemoryPublisher
from memory_service.engine.pipeline import OfflineMemoryPipeline

logger = logging.getLogger(__name__)


class JobWorker:
    """
    离线记忆异步任务工作进程 (Worker)：
    从 MySQL ltm_job 队列表中拉取 PENDING 任务，安全执行重试、编译、合并或全量发布。
    """

    def __init__(
        self,
        session_factory,
        max_retries: int = 3,
    ):
        self.session_factory = session_factory
        self.max_retries = max_retries
        self._running = False

    async def fetch_next_job(self, session: AsyncSession) -> LtmJobModel | None:
        """原子拉取并锁定下一条待执行任务"""
        stmt = (
            select(LtmJobModel)
            .where(LtmJobModel.status == JobStatus.PENDING.value)
            .order_by(LtmJobModel.created_at.asc())
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        result = await session.execute(stmt)
        job = result.scalar_one_or_none()
        if job:
            job.status = JobStatus.PROCESSING.value
            job.updated_at = datetime.now(timezone.utc)
            await session.flush()
        return job

    async def execute_job(self, job: LtmJobModel, session: AsyncSession) -> None:
        """执行具体任务逻辑"""
        repo = MemoryRepository(session)
        publisher = MemoryPublisher(repo)

        if job.job_type == "PUBLISH":
            if not job.memory_id or not job.version:
                raise ValueError("PUBLISH job requires memory_id and version")
            await publisher.publish(job.memory_id, job.version)

        elif job.job_type == "RETRY_INDEX":
            if not job.memory_id or not job.version:
                raise ValueError("RETRY_INDEX job requires memory_id and version")
            # 重新同步向量索引
            ver = await repo.get_version(job.memory_id, job.version)
            if ver and ver.pack:
                task_type = ver.procedure_detail.task_type if ver.procedure_detail else "GENERAL"
                await publisher.vector_repo.upsert_vector(
                    memory_id=job.memory_id,
                    memory_version=job.version,
                    user_id=ver.data_json.get("user_id", "system"),
                    memory_type=ver.data_json.get("memory_type", "FACT"),
                    task_type=task_type,
                    embedding=ver.data_json.get("embedding"),
                )

        elif job.job_type == "NOOP":
            pass

        else:
            logger.warning("Unrecognized job_type: %s", job.job_type)

    async def run_once(self) -> bool:
        """执行单次任务拉取与处理循环"""
        async with self.session_factory() as session:
            try:
                job = await self.fetch_next_job(session)
                if not job:
                    return False

                job_id = job.id
                job_type = job.job_type
                logger.info("Processing job %s (type=%s)", job_id, job_type)

                try:
                    await self.execute_job(job, session)
                    job.status = JobStatus.COMPLETED.value
                    job.error_message = None
                    job.updated_at = datetime.now(timezone.utc)
                    await session.commit()
                    logger.info("Job %s completed successfully", job_id)
                except Exception as e:
                    logger.exception("Job %s failed with error: %s", job_id, str(e))
                    job.retry_count += 1
                    job.error_message = str(e)
                    job.updated_at = datetime.now(timezone.utc)
                    if job.retry_count >= self.max_retries:
                        job.status = JobStatus.FAILED.value
                    else:
                        job.status = JobStatus.PENDING.value
                    await session.commit()

                return True
            except Exception as outer_err:
                await session.rollback()
                logger.exception("Worker run_once error: %s", str(outer_err))
                return False

    async def run_loop(self, poll_interval: float = 2.0) -> None:
        """持续后台轮询"""
        self._running = True
        logger.info("JobWorker started with poll_interval=%.2fs", poll_interval)
        while self._running:
            had_work = await self.run_once()
            if not had_work:
                await asyncio.sleep(poll_interval)

    def stop(self) -> None:
        self._running = False
        logger.info("JobWorker stop signal received")
