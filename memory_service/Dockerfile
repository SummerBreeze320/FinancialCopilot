# ==============================================================================
# Financial Copilot - Long-Term Memory Service Dockerfile
# ==============================================================================
FROM python:3.12-slim

# 设置工作目录与环境变量
WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app

# 安装必要的系统运行时依赖
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

# 复制依赖定义并安装 Python 依赖包
COPY pyproject.toml README.md ./
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir .

# 复制源码
COPY . .

# 默认暴露出服务端口
EXPOSE 8000

# 默认启动 FastAPI 在线读取与加工微服务 (可由 docker-compose 重写 command)
CMD ["uvicorn", "memory_service.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
