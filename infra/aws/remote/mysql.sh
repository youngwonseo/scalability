#!/bin/bash
# MySQL host roles. Usage (as root):
#   mysql.sh primary  <server_id>            [RESET=1 wipes data]
#   mysql.sh replica  <server_id> <source_ip>
#   mysql.sh shard    <server_id>
# Env: BUFFER_POOL (default 10G; m6i.xlarge has 16 GB)
set -euo pipefail
MODE=$1; SERVER_ID=$2; SOURCE=${3:-}
BUFFER_POOL=${BUFFER_POOL:-10G}

docker rm -f mysql mysqld-exporter >/dev/null 2>&1 || true
if [[ "${RESET:-0}" == 1 ]]; then rm -rf /data/mysql; fi
mkdir -p /data/mysql /etc/sns-mysql/conf /etc/sns-mysql/init

cat > /etc/sns-mysql/conf/sns.cnf <<CNF
[mysqld]
server_id = ${SERVER_ID}
gtid_mode = ON
enforce_gtid_consistency = ON
innodb_buffer_pool_size = ${BUFFER_POOL}
innodb_redo_log_capacity = 2G
innodb_flush_log_at_trx_commit = 1
sync_binlog = 1
max_connections = 2000
skip-name-resolve
CNF

rm -f /etc/sns-mysql/init/*
ENV=(-e MYSQL_ROOT_PASSWORD=root)
case "$MODE" in
  primary|shard)
    ENV+=(-e MYSQL_DATABASE=sns -e MYSQL_USER=sns -e MYSQL_PASSWORD=sns)
    cat > /etc/sns-mysql/init/01-users.sql <<'SQL'
CREATE USER 'repl'@'%' IDENTIFIED BY 'repl';
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%';
SQL
    ;;
  replica)
    cat > /etc/sns-mysql/init/01-replica.sql <<SQL
CHANGE REPLICATION SOURCE TO SOURCE_HOST = '${SOURCE}', SOURCE_USER = 'repl', SOURCE_PASSWORD = 'repl',
  SOURCE_AUTO_POSITION = 1, GET_SOURCE_PUBLIC_KEY = 1;
START REPLICA;
SET PERSIST super_read_only = ON;
SQL
    ;;
  *) echo "unknown mode $MODE" >&2; exit 2 ;;
esac

docker run -d --name mysql --network host --restart unless-stopped --ulimit nofile=65535:65535 "${ENV[@]}" \
  -v /data/mysql:/var/lib/mysql \
  -v /etc/sns-mysql/conf:/etc/mysql/conf.d:ro \
  -v /etc/sns-mysql/init:/docker-entrypoint-initdb.d:ro \
  mysql:8.4 >/dev/null

# The entrypoint runs a temporary server first; wait for the real one (root password set, port open)
for _ in $(seq 1 180); do
  if docker exec mysql mysqladmin ping -h 127.0.0.1 -uroot -proot --silent >/dev/null 2>&1; then break; fi
  sleep 2
done
docker exec mysql mysqladmin ping -h 127.0.0.1 -uroot -proot --silent

EXTRA=()
[[ "$MODE" == replica ]] && EXTRA+=(--collect.perf_schema.replication_applier_status_by_worker)
docker run -d --name mysqld-exporter --network host --restart unless-stopped \
  -e MYSQLD_EXPORTER_PASSWORD=root prom/mysqld-exporter:v0.17.2 \
  --mysqld.address=127.0.0.1:3306 --mysqld.username=root "${EXTRA[@]}" >/dev/null
echo "mysql $MODE ready"
