#!/bin/bash
# (Re)starts the app. /opt/sns/sns.jar and /opt/sns/app.env must be in place.
# Runs from the extracted jar with >= 4 virtual-thread carriers (see README: deadlock on small hosts).
set -euo pipefail
docker rm -f app >/dev/null 2>&1 || true
if [[ ! -f /opt/sns/app/sns.jar || /opt/sns/sns.jar -nt /opt/sns/app/sns.jar ]]; then
  rm -rf /opt/sns/app
  docker run --rm -v /opt/sns:/opt/sns eclipse-temurin:25-jre \
    java -Djarmode=tools -jar /opt/sns/sns.jar extract --destination /opt/sns/app >/dev/null
fi
docker run -d --name app --network host --ulimit nofile=1048576:1048576 \
  --env-file /opt/sns/app.env -v /opt/sns/app:/app:ro -w /app eclipse-temurin:25-jre \
  java -XX:MaxRAMPercentage=75 -Djdk.virtualThreadScheduler.parallelism=4 -jar sns.jar >/dev/null
for _ in $(seq 1 90); do
  if curl -sf -o /dev/null http://127.0.0.1:8080/actuator/health; then echo "app ready"; exit 0; fi
  if [[ "$(docker inspect -f '{{.State.Running}}' app)" != true ]]; then docker logs --tail 50 app; exit 1; fi
  sleep 2
done
docker logs --tail 50 app; exit 1
