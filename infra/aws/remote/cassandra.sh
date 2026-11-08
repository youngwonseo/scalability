#!/bin/bash
# Usage: cassandra.sh <seed_ip>   [RESET=1 wipes data]. /opt/jmx holds the JMX exporter agent + config.
set -euo pipefail
SEED=$1
IP=$(hostname -I | awk '{print $1}')
docker rm -f cassandra >/dev/null 2>&1 || true
if [[ "${RESET:-0}" == 1 ]]; then rm -rf /data/cassandra; fi
mkdir -p /data/cassandra && chown 999:999 /data/cassandra
docker run -d --name cassandra --network host --restart unless-stopped --ulimit nofile=1048576:1048576 \
  -e CASSANDRA_CLUSTER_NAME=sns -e CASSANDRA_SEEDS="$SEED" -e CASSANDRA_DC=datacenter1 \
  -e CASSANDRA_ENDPOINT_SNITCH=GossipingPropertyFileSnitch \
  -e CASSANDRA_LISTEN_ADDRESS="$IP" -e CASSANDRA_BROADCAST_ADDRESS="$IP" \
  -e CASSANDRA_RPC_ADDRESS=0.0.0.0 -e CASSANDRA_BROADCAST_RPC_ADDRESS="$IP" \
  -e MAX_HEAP_SIZE=8G -e HEAP_NEWSIZE=2G \
  -e JVM_EXTRA_OPTS="-javaagent:/opt/jmx/agent.jar=7070:/opt/jmx/config.yml" \
  -v /data/cassandra:/var/lib/cassandra -v /opt/jmx:/opt/jmx:ro cassandra:5.0.4 >/dev/null
# Joined when this node reports itself Up/Normal
for _ in $(seq 1 180); do
  if docker exec cassandra nodetool status 2>/dev/null | grep -q "^UN  ${IP} "; then echo "cassandra ready ($IP)"; exit 0; fi
  sleep 5
done
docker logs --tail 50 cassandra; exit 1
