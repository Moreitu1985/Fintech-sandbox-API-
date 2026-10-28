# Build stage: install dependencies and train the model reproducibly (fixed seed, ~15 seconds).
FROM python:3.13-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY fraud_service ./fraud_service
RUN python -m fraud_service.train /app/model

# Runtime stage: no compilers, non-root user, model baked in.
FROM python:3.13-slim
RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/* && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /usr/local/lib/python3.13/site-packages /usr/local/lib/python3.13/site-packages
COPY --from=build /usr/local/bin/uvicorn /usr/local/bin/uvicorn
COPY --from=build /app/fraud_service ./fraud_service
COPY --from=build /app/model ./model
# No package managers at runtime: removes pip/setuptools (and their vendored urllib3, msgpack...)
# so nothing can be installed into the running container, and their CVEs don't ship.
RUN rm -rf /usr/local/lib/python3.13/site-packages/pip /usr/local/lib/python3.13/site-packages/pip-* \
           /usr/local/lib/python3.13/site-packages/setuptools /usr/local/lib/python3.13/site-packages/setuptools-* \
           /usr/local/lib/python3.13/site-packages/pkg_resources /usr/local/lib/python3.13/site-packages/_distutils_hack \
           /usr/local/lib/python3.13/site-packages/distutils-precedence.pth \
           /usr/local/bin/pip /usr/local/bin/pip3 /usr/local/bin/pip3.13
USER app
EXPOSE 8090
ENV FRAUD_MODEL_DIR=/app/model
CMD ["uvicorn", "fraud_service.api:app", "--host", "0.0.0.0", "--port", "8090", "--no-server-header"]
