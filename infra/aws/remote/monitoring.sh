#!/bin/bash
# Prometheus + Grafana on the load generator. /opt/bench holds the repo files copied by run_all.py;
# scrape targets are file_sd JSON files in /opt/mon/targets written by run_all.py.
set -euo pipefail
mkdir -p /opt/mon/targets /opt/mon/prometheus-data && chown -R 65534:65534 /opt/mon/prometheus-data
for job in node app mysql cassandra redis; do [[ -f /opt/mon/targets/$job.json ]] || echo '[]' > /opt/mon/targets/$job.json; done
cat > /opt/mon/prometheus.yml <<'YML'
global:
  scrape_interval: 5s
scrape_configs:
  - job_name: node
    file_sd_configs: [{ files: ["/targets/node.json"], refresh_interval: 5s }]
  - job_name: app
    metrics_path: /actuator/prometheus
    file_sd_configs: [{ files: ["/targets/app.json"], refresh_interval: 5s }]
  - job_name: mysql
    file_sd_configs: [{ files: ["/targets/mysql.json"], refresh_interval: 5s }]
  - job_name: cassandra
    file_sd_configs: [{ files: ["/targets/cassandra.json"], refresh_interval: 5s }]
  - job_name: redis
    file_sd_configs: [{ files: ["/targets/redis.json"], refresh_interval: 5s }]
YML
docker rm -f prometheus grafana >/dev/null 2>&1 || true
docker run -d --name prometheus --network host --restart unless-stopped \
  -v /opt/mon/prometheus.yml:/etc/prometheus/prometheus.yml:ro -v /opt/mon/targets:/targets:ro \
  -v /opt/mon/prometheus-data:/prometheus prom/prometheus:v3.5.0 \
  --config.file=/etc/prometheus/prometheus.yml --web.enable-remote-write-receiver \
  --storage.tsdb.retention.time=7d >/dev/null
docker run -d --name grafana --network host --restart unless-stopped \
  -e GF_AUTH_ANONYMOUS_ENABLED=true -e GF_AUTH_ANONYMOUS_ORG_ROLE=Admin -e GF_AUTH_DISABLE_LOGIN_FORM=true \
  -e GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH=/var/lib/grafana/dashboards/scalability.json \
  -v /opt/bench/infra/aws/remote/grafana-datasource.yml:/etc/grafana/provisioning/datasources/prometheus.yml:ro \
  -v /opt/bench/infra/local/grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro \
  -v /opt/bench/infra/local/grafana/dashboards:/var/lib/grafana/dashboards:ro grafana/grafana:12.1.0 >/dev/null
for _ in $(seq 1 30); do curl -sf -o /dev/null http://127.0.0.1:9090/-/ready && { echo "monitoring ready"; exit 0; }; sleep 1; done
exit 1
