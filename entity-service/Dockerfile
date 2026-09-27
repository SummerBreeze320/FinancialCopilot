FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN groupadd --system entity && useradd --system --gid entity entity

COPY pyproject.toml README.md ./
COPY app ./app
RUN pip install --no-cache-dir .

USER entity
EXPOSE 8800

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8800"]
