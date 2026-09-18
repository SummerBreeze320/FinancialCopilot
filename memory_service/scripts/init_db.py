import asyncio
import logging
from memory_service.config import settings
from memory_service.infrastructure.mysql.session import engine
from memory_service.infrastructure.mysql.models import Base
from memory_service.infrastructure.milvus.client import MilvusClientWrapper

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s - %(message)s")
logger = logging.getLogger("init_db")


async def init_mysql():
    logger.info("Initializing MySQL authoritative tables...")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("MySQL tables initialized successfully.")


def init_milvus():
    logger.info("Initializing Milvus vector collection: %s", settings.milvus_collection)
    client = MilvusClientWrapper()
    coll = client.init_collection()
    logger.info("Milvus collection '%s' initialized (has_collection=%s)", settings.milvus_collection, coll is not None)


async def main():
    logger.info("Starting database and index initialization...")
    try:
        await init_mysql()
    except Exception as e:
        logger.error("MySQL initialization error: %s", e)

    try:
        init_milvus()
    except Exception as e:
        logger.warning("Milvus initialization skipped or failed (if not running): %s", e)

    logger.info("Initialization finished.")


if __name__ == "__main__":
    asyncio.run(main())
