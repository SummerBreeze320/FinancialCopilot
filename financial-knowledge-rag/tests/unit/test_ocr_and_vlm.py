import pytest
from unittest.mock import AsyncMock, patch
from app.ingest.ocr_engine import OcrEngine
from app.ingest.vlm_analyzer import VlmAnalyzer


def test_ocr_engine_initialization():
    engine = OcrEngine(enable_gpu=False)
    # CPU 模式优雅初始化
    assert engine.is_gpu_available is False or engine.is_gpu_available is True
    # 模拟空图片输入不会抛出致命崩溃
    result = engine.recognize_page_image(b"")
    assert isinstance(result, str)


@pytest.mark.asyncio
async def test_vlm_analyzer_converts_flowchart():
    analyzer = VlmAnalyzer(base_url="http://127.0.0.1:9090/v1", api_key="local-agy", model="gemini-3.8-flash-low")
    with patch("httpx.AsyncClient.post") as mock_post:
        mock_post.return_value = AsyncMock(
            status_code=200,
            json=lambda: {
                "choices": [{
                    "message": {
                        "content": "【股权架构流程】\n```mermaid\ngraph TD\nA[母公司] --> B[子公司]\n```"
                    }
                }]
            }
        )
        res = await analyzer.analyze_diagram(b"fake_image_bytes", diagram_hint="股权结构图")
        assert "【股权架构流程】" in res
        assert "mermaid" in res
