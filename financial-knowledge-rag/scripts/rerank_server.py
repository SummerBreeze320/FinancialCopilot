"""独立运行的 BGE-Reranker-Large 重排微服务容器端点 (端口 8088)。"""

import argparse
import logging
import math
from typing import List
from fastapi import FastAPI
from pydantic import BaseModel, Field
import uvicorn

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("rerank-server")

app = FastAPI(title="Rerank Microservice", version="1.0.0")

_model = None
_model_name = "BAAI/bge-reranker-large"
_device = "cuda"


class RerankRequest(BaseModel):
    model: str = Field(default="BAAI/bge-reranker-large")
    query: str = Field(...)
    texts: List[str] = Field(..., description="待重排的候选切片文本列表")


class RerankResultItem(BaseModel):
    index: int
    score: float


def load_model(model_name: str, device: str):
    global _model
    from sentence_transformers import CrossEncoder
    import torch

    actual_device = "cuda" if torch.cuda.is_available() and device == "cuda" else "cpu"
    logger.info("正在加载 CrossEncoder 重排模型: %s (设备: %s)", model_name, actual_device)
    _model = CrossEncoder(model_name, device=actual_device)
    logger.info("CrossEncoder 模型加载完成，就绪提供重排服务。")


@app.get("/health")
def health():
    return {"status": "UP", "model": _model_name, "device": _device}


@app.post("/v1/rerank", response_model=List[RerankResultItem])
@app.post("/rerank", response_model=List[RerankResultItem])
def rerank_endpoint(req: RerankRequest) -> List[RerankResultItem]:
    """处理重排请求，返回归一化相关度得分。"""
    global _model
    if _model is None:
        load_model(_model_name, _device)

    if not req.texts:
        return []

    pairs = [[req.query, t] for t in req.texts]
    raw_scores = _model.predict(pairs)

    results: List[RerankResultItem] = []
    for idx, s in enumerate(raw_scores):
        val = float(s)
        norm = 1.0 / (1.0 + math.exp(-val)) if abs(val) < 20 else (1.0 if val > 0 else 0.0)
        results.append(RerankResultItem(index=idx, score=round(norm, 4)))

    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="独立 Docker/本地 Rerank 微服务")
    parser.add_argument("--port", type=int, default=8088, help="监听端口 (默认 8088)")
    parser.add_argument("--model", default="BAAI/bge-reranker-large", help="模型名称或本地权重路径")
    parser.add_argument("--device", default="cuda", help="推理设备 (cuda/cpu)")
    args = parser.parse_args()

    _model_name = args.model
    _device = args.device
    uvicorn.run(app, host="0.0.0.0", port=args.port)
