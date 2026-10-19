from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Request, Response

from app.api.dependencies import get_pipeline, get_repository
from app.models.api import CatalogEntity, RecognitionRequest, RecognitionResponse


router = APIRouter(prefix="/v1/entities", tags=["entities"])


class EntityNotFoundError(LookupError):
    pass


@router.post("/recognize", response_model=RecognitionResponse)
async def recognize(
    payload: RecognitionRequest,
    request: Request,
    response: Response,
    pipeline=Depends(get_pipeline),
) -> RecognitionResponse:
    request_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
    response.headers["X-Request-ID"] = request_id
    return await pipeline.recognize(payload, request_id)


@router.get("/{entity_id:path}", response_model=CatalogEntity)
async def get_entity(entity_id: str, repository=Depends(get_repository)) -> CatalogEntity:
    entity = await repository.get_entity(entity_id)
    if entity is None:
        raise EntityNotFoundError(entity_id)
    return CatalogEntity(
        id=entity.id,
        category=entity.category,
        canonical_value=entity.canonical_value,
        metadata=entity.metadata,
    )
