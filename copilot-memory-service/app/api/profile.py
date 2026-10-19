"""用户画像 API"""

import logging
from datetime import datetime
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.engine.profile_engine import profile_engine
from app.models.schemas import UserProfile, ProfileDiffResponse

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/v1/users", tags=["profile"])


class ProfileExtractRequest(BaseModel):
    dialogue: str


@router.get("/{user_id}/profile", response_model=UserProfile)
async def get_profile(user_id: str):
    """获取用户当前画像（仅返回当前有效维度）"""
    return await profile_engine.get_profile(user_id)


@router.post("/{user_id}/profile/extract")
async def extract_profile(user_id: str, body: ProfileExtractRequest):
    """从对话内容中提取画像并更新"""
    result = await profile_engine.extract_and_update(user_id, body.dialogue)
    return result


@router.get("/{user_id}/profile/diff", response_model=ProfileDiffResponse)
async def get_profile_diff(
    user_id: str,
    since: datetime | None = None,
):
    """获取用户画像变化历史"""
    return await profile_engine.get_profile_diff(user_id, since)
