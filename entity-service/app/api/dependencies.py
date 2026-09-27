from __future__ import annotations

from dataclasses import dataclass


class ServiceUnavailableError(RuntimeError):
    pass


@dataclass(slots=True)
class Runtime:
    pipeline: object | None = None
    repository: object | None = None
    indexing_service: object | None = None
    startup_error: str | None = None


runtime = Runtime()


def get_pipeline():
    if runtime.pipeline is None:
        raise ServiceUnavailableError(runtime.startup_error or "recognition pipeline unavailable")
    return runtime.pipeline


def get_repository():
    if runtime.repository is None:
        raise ServiceUnavailableError(runtime.startup_error or "entity repository unavailable")
    return runtime.repository


def get_indexing_service():
    if runtime.indexing_service is None:
        raise ServiceUnavailableError(runtime.startup_error or "indexing service unavailable")
    return runtime.indexing_service
