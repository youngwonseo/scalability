#!/bin/bash
# Cache only: no persistence, LRU eviction. m6i.large has 8 GB.
set -euo pipefail
docker rm -f redis redis-exporter >/dev/null 2>&1 || true
docker run -d --name redis --network host --restart unless-stopped redis:7.4 \
  redis-server --save "" --appendonly no --maxmemory 6gb --maxmemory-policy allkeys-lru >/dev/null
docker run -d --name redis-exporter --network host --restart unless-stopped \
  -e REDIS_ADDR=redis://127.0.0.1:6379 oliver006/redis_exporter:v1.74.0 >/dev/null
for _ in $(seq 1 30); do docker exec redis redis-cli ping 2>/dev/null | grep -q PONG && { echo "redis ready"; exit 0; }; sleep 1; done
exit 1
