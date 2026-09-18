import logging
from pymilvus import connections, utility, FieldSchema, CollectionSchema, DataType, Collection
from memory_service.config import settings

logger = logging.getLogger(__name__)


class MilvusClientManager:
    """Milvus 连接生命周期与 Collection 管理器"""

    def __init__(self):
        self.collection_name = settings.milvus_collection
        self.dim = settings.milvus_dim
        self._connected = False

    def connect(self) -> None:
        """建立 Milvus 连接"""
        if self._connected:
            return
        try:
            connections.connect(
                alias="default",
                host=settings.milvus_host,
                port=settings.milvus_port,
                user=settings.milvus_user,
                password=settings.milvus_password,
            )
            self._connected = True
            logger.info("Successfully connected to Milvus at %s:%s", settings.milvus_host, settings.milvus_port)
            self._ensure_collection()
        except Exception as e:
            logger.warning("Milvus connection not established (will run in mock/offline mode if needed): %s", e)

    def _ensure_collection(self) -> None:
        """保证 ltm_memory_vector Collection 存在并建立索引"""
        if not self._connected:
            return
        if utility.has_collection(self.collection_name):
            return

        fields = [
            FieldSchema(name="vector_id", dtype=DataType.VARCHAR, max_length=128, is_primary=True, description="版本化向量主键 memory_id:version"),
            FieldSchema(name="memory_id", dtype=DataType.VARCHAR, max_length=64, description="对应 MySQL 记忆ID"),
            FieldSchema(name="memory_version", dtype=DataType.INT64, description="对应记忆版本号"),
            FieldSchema(name="user_id", dtype=DataType.VARCHAR, max_length=64, description="用户隔离标识"),
            FieldSchema(name="memory_type", dtype=DataType.VARCHAR, max_length=32, description="FACT / PREFERENCE / PROCEDURE"),
            FieldSchema(name="task_type", dtype=DataType.VARCHAR, max_length=64, description="任务类型"),
            FieldSchema(name="embedding", dtype=DataType.FLOAT_VECTOR, dim=self.dim, description="语义向量"),
        ]
        schema = CollectionSchema(fields=fields, description="长期记忆不可变版本向量索引")
        collection = Collection(name=self.collection_name, schema=schema)

        # 建立 HNSW / IVF_FLAT 索引
        index_params = {
            "metric_type": "COSINE",
            "index_type": "HNSW",
            "params": {"M": 16, "efConstruction": 200}
        }
        collection.create_index(field_name="embedding", index_params=index_params)
        collection.load()
        logger.info("Created and loaded Milvus collection: %s", self.collection_name)

    def get_collection(self) -> Collection | None:
        if not self._connected:
            return None
        return Collection(self.collection_name)

    def disconnect(self) -> None:
        if self._connected:
            connections.disconnect("default")
            self._connected = False


milvus_client = MilvusClientManager()
