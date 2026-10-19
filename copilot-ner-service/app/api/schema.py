from fastapi import APIRouter

from app.models.api import RecognitionResponse


router = APIRouter(prefix="/v1/schema", tags=["schema"])


@router.get("/recognition-response")
async def recognition_response_schema():
    return RecognitionResponse.model_json_schema()
