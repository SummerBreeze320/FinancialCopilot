import uuid
import pytest
from sqlalchemy import select
from memory_service.domain.enums import JobStatus
from memory_service.infrastructure.mysql.models import LtmJobModel
from memory_service.jobs.worker import JobWorker


@pytest.mark.asyncio
async def test_job_worker_execution(db_session, session_factory):
    # 插入一个 PENDING 任务
    job_id = str(uuid.uuid4())
    job = LtmJobModel(
        id=job_id,
        job_type="NOOP",
        status=JobStatus.PENDING.value,
    )
    db_session.add(job)
    await db_session.commit()

    worker = JobWorker(session_factory=session_factory, max_retries=2)
    processed = await worker.run_once()
    assert processed is True

    # 验证任务状态变为 COMPLETED
    async with session_factory() as session:
        stmt = select(LtmJobModel).where(LtmJobModel.id == job_id)
        res = await session.execute(stmt)
        updated_job = res.scalar_one_or_none()
        assert updated_job is not None
        assert updated_job.status == JobStatus.COMPLETED.value
