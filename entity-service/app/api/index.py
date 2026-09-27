from __future__ import annotations

import secrets

from fastapi import APIRouter, Depends, Header
from fastapi.responses import JSONResponse

from app.api.dependencies import get_indexing_service
from app.config import Settings, get_settings
from app.indexing.service import RebuildAlreadyRunning
from app.models.api import RebuildRequest


router = APIRouter(prefix="/v1/entities/index", tags=["index"])


class UnauthorizedError(PermissionError):
    pass


class JobNotFoundError(LookupError):
    pass


def _authorize(authorization: str | None, settings: Settings) -> None:
    expected = f"Bearer {settings.entity_admin_token}"
    if authorization is None or not secrets.compare_digest(authorization, expected):
        raise UnauthorizedError("invalid admin token")


def _job_payload(job) -> dict:
    return {
        "job_id": job.job_id,
        "status": job.status,
        "processed": job.processed,
        "total": job.total,
        "version_id": job.version_id,
        "error": job.error,
    }


@router.post("/rebuild", status_code=202)
async def rebuild(
    payload: RebuildRequest,
    authorization: str | None = Header(default=None),
    indexing=Depends(get_indexing_service),
    settings: Settings = Depends(get_settings),
):
    _authorize(authorization, settings)
    try:
        job = await indexing.start_rebuild(
            payload.catalog_path or settings.entity_catalog_path,
            force=payload.force,
        )
    except RebuildAlreadyRunning:
        raise
    return JSONResponse(_job_payload(job), status_code=202)


@router.get("/jobs/{job_id}")
async def job_status(job_id: str, indexing=Depends(get_indexing_service)):
    job = indexing.get_job(job_id)
    if job is None:
        raise JobNotFoundError(job_id)
    return _job_payload(job)
