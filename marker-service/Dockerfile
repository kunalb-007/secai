FROM python:3.11-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    poppler-utils \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install Python dependencies
RUN pip install --no-cache-dir \
    marker-pdf==1.10.2 \
    flask \
    gunicorn

# Copy application
COPY server.py .

EXPOSE 8100

CMD ["gunicorn", "--bind", "0.0.0.0:8100", "--timeout", "300", "--workers", "1", "server:app"]