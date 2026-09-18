from typing import AsyncGenerator
from contextlib import asynccontextmanager
from sqlalchemy.ext.asyncio import create_async_engine, AsyncSession, async_sessionmaker
from memory_service.config import settings
from memory_service.infrastructure.mysql.models import Base

# 创建异步引擎
engine_kwargs = {"echo": settings.debug}
if not settings.use_sqlite:
    engine_kwargs.update({
        "pool_size": settings.mysql_pool_size,
        "max_overflow": settings.mysql_max_overflow,
        "pool_recycle": 3600,
        "pool_pre_ping": True,
    })

async_engine = create_async_engine(settings.mysql_async_url, **engine_kwargs)

async_session_maker = async_sessionmaker(
    bind=async_engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autoflush=False,
)


async def init_db() -> None:
    """初始化数据库表结构 (用于测试与本地自动构建)"""
    async with async_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


async def close_db() -> None:
    """关闭引擎连接池"""
    await async_engine.dispose()


@asynccontextmanager
async def get_db_session() -> AsyncGenerator[AsyncSession, None]:
    """供服务层使用的上下文管理器"""
    async with async_session_maker() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


async def get_session_dependency() -> AsyncGenerator[AsyncSession, None]:
    """供 FastAPI 路由注入使用的 Session 依赖项"""
    async with async_session_maker() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
