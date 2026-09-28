from fastapi import APIRouter

from app.models.dsl import FundScreeningDsl

router = APIRouter(prefix="/v1/fund-screening", tags=["schema"])


@router.get("/schema")
async def dsl_schema() -> dict:
    return FundScreeningDsl.model_json_schema()
