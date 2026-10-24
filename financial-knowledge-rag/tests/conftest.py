"""Pytest 全局夹具配置。"""

import sys
from pathlib import Path

# 确保 app 目录加入 Python 路径
PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))
