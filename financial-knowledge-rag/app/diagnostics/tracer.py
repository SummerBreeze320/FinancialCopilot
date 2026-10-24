"""检索链路耗时度量与分段统计器。"""

from contextlib import contextmanager
import time
from typing import Dict, Generator


class LatencyTracer:
    """细粒度记录向量嵌入、双路召回、神经重排各阶段耗时毫秒数。"""

    def __init__(self) -> None:
        self.latencies: Dict[str, float] = {}

    @contextmanager
    def measure(self, stage_name: str) -> Generator[None, None, None]:
        """以上下文管理器模式精确统计代码块执行毫秒数。"""
        start = time.perf_counter()
        try:
            yield
        finally:
            elapsed_ms = (time.perf_counter() - start) * 1000.0
            self.latencies[stage_name] = round(elapsed_ms, 2)

    def get_breakdown_ms(self) -> Dict[str, float]:
        """获取分段耗时明细与全流程总耗时。"""
        result = dict(self.latencies)
        total = sum(self.latencies.values())
        result["total_latency"] = round(total, 2)
        return result
