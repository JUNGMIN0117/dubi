ARG PYTHON_IMAGE=python:3.11-slim
FROM ${PYTHON_IMAGE}

WORKDIR /app
COPY archiver/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY archiver/src ./src

ENV PYTHONPATH=/app/src
CMD ["python", "-m", "main"]
