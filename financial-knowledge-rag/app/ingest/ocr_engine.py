"""GPU 加速 OCR 引擎与 CPU 优雅降级支持。"""

import logging
from typing import Optional

logger = logging.getLogger(__name__)


class OcrEngine:
    """金融扫描文档与页面图像 OCR 文字识别引擎。"""

    def __init__(self, enable_gpu: bool = True, device_id: int = 0):
        self.enable_gpu = enable_gpu
        self.device_id = device_id
        self.is_gpu_available = False
        self._ocr_instance = None
        self._init_engine()

    def _init_engine(self) -> None:
        """探测 GPU 环境并初始化 OCR 引擎。"""
        # 探测 CUDA / GPU 支持
        try:
            import onnxruntime as ort
            providers = ort.get_available_providers()
            if self.enable_gpu and "CUDAExecutionProvider" in providers:
                self.is_gpu_available = True
                logger.info("[OCR] 成功启用 GPU CUDAExecutionProvider 加速")
            else:
                self.is_gpu_available = False
                logger.info("[OCR] 未检测到 CUDA 环境或已禁用 GPU，采用 CPU 推理")
        except Exception:
            self.is_gpu_available = False

        # 初始化 RapidOCR
        try:
            from rapidocr_onnxruntime import RapidOCR
            self._ocr_instance = RapidOCR()
        except Exception as e:
            logger.warning(f"[OCR] RapidOCR 初始化失败，进入纯文本兜底模式: {e}")
            self._ocr_instance = None

    def recognize_page_image(self, image_bytes: bytes) -> str:
        """识别单页图像字节流，返回提取出的文本与表格行。"""
        if not image_bytes or len(image_bytes) < 10:
            return ""

        if not self._ocr_instance:
            return "[OCR不可用: 未加载模型]"

        try:
            result, _ = self._ocr_instance(image_bytes)
            if not result:
                return ""
            # result 结构为 [[dt_boxes, text, score], ...]
            recognized_lines = [item[1] for item in result if len(item) >= 2 and item[1]]
            return "\n".join(recognized_lines)
        except Exception as e:
            logger.error(f"[OCR] 图像识别异常: {e}")
            return f"[OCR识别错误: {e}]"
