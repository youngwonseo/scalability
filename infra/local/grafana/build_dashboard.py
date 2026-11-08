#!/usr/bin/env python3
"""Generate the Grafana dashboard (dashboards/scalability.json). Edit panels here, then run this script."""

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "dashboards" / "scalability.json"
APP = 'job="app", uri!~"/actuator.*"'

# (row title, [(panel title, unit, [(expr, legend)])])
ROWS = [
    ("Client (k6)", [
        ("k6 RPS by type", "reqps",
         [('sum by (type) (rate(k6_http_reqs_total[15s]))', "{{type}}")]),
        ("k6 latency by type", "s",
         [('max by (type) (k6_http_req_duration_p99)', "p99 {{type}}"),
          ('max by (type) (k6_http_req_duration_p95)', "p95 {{type}}")]),
        ("k6 error rate", "percentunit",
         [('max by (type) (k6_http_req_failed_rate)', "{{type}}")]),
        ("k6 dropped iterations/s", "short",
         [('sum(rate(k6_dropped_iterations_total[15s]))', "dropped")]),
        ("Stale reads (read-after-write probe)", "percentunit",
         [('max(k6_stale_reads_rate)', "404 right after create")]),
    ]),
    ("App", [
        ("Server RPS by instance", "reqps",
         [(f'sum by (instance) (rate(http_server_requests_seconds_count{{{APP}}}[15s]))', "{{instance}}")]),
        ("Server latency p99 by instance", "s",
         [(f'histogram_quantile(0.99, sum by (le, instance) '
           f'(rate(http_server_requests_seconds_bucket{{{APP}}}[30s])))', "{{instance}}")]),
        ("Process CPU", "percentunit",
         [('process_cpu_usage{job="app"}', "{{instance}}")]),
        ("DB connection pools (summed over apps)", "short",
         [('sum by (pool) (hikaricp_connections_active{job="app"})', "active {{pool}}"),
          ('sum by (pool) (hikaricp_connections_pending{job="app"})', "pending {{pool}}")]),
        ("Connection acquire time (max)", "s",
         [('max by (pool) (hikaricp_connections_acquire_seconds_max{job="app"})', "{{pool}}")]),
        ("GC pause time/s", "s",
         [('sum by (instance) (rate(jvm_gc_pause_seconds_sum{job="app"}[30s]))', "{{instance}}")]),
    ]),
    ("MySQL (per node)", [
        ("Statements/s", "short",
         [('sum by (node, command) (rate(mysql_global_status_commands_total'
           '{command=~"select|insert|update|delete"}[15s]))', "{{node}} {{command}}")]),
        ("Threads running", "short",
         [('mysql_global_status_threads_running', "{{node}}")]),
        ("Row lock waits/s", "short",
         [('rate(mysql_global_status_innodb_row_lock_waits[15s])', "{{node}}")]),
        ("Row lock time ms/s", "short",
         [('rate(mysql_global_status_innodb_row_lock_time[15s])', "{{node}}")]),
        ("Buffer pool disk reads/s", "short",
         [('rate(mysql_global_status_innodb_buffer_pool_reads[15s])', "{{node}}")]),
        ("Redo log fsyncs/s", "short",
         [('rate(mysql_global_status_innodb_os_log_fsyncs[15s])', "{{node}}")]),
    ]),
    ("Cache (S4)", [
        ("Cache hit ratio", "percentunit",
         [('sum by (cache) (rate(sns_cache_requests_total{result="hit"}[30s]))'
           ' / sum by (cache) (rate(sns_cache_requests_total[30s]))', "{{cache}}")]),
        ("Redis commands/s", "short",
         [('rate(redis_commands_processed_total[15s])', "commands")]),
        ("Redis CPU", "percentunit",
         [('rate(redis_cpu_sys_seconds_total[15s]) + rate(redis_cpu_user_seconds_total[15s])', "cpu")]),
        ("Redis memory", "bytes",
         [('redis_memory_used_bytes', "used"), ('redis_memory_max_bytes', "max")]),
        ("Redis evictions/s", "short",
         [('rate(redis_evicted_keys_total[15s])', "evicted")]),
    ]),
    ("Replication (S3)", [
        ("Replication lag (last applied transaction)", "s",
         [('max by (node) (mysql_perf_schema_last_applied_transaction_end_apply_timestamp_seconds'
           ' - mysql_perf_schema_last_applied_transaction_original_commit_timestamp_seconds)', "{{node}}")]),
        ("Seconds behind source", "s",
         [('mysql_slave_status_seconds_behind_source', "{{node}}")]),
        ("Replication threads running", "short",
         [('mysql_slave_status_replica_io_running + mysql_slave_status_replica_sql_running', "{{node}} (2 = ok)")]),
    ]),
]


def build():
    ds = {"type": "prometheus", "uid": "prometheus"}
    panels, pid, y = [], 1, 0
    for row_title, items in ROWS:
        panels.append({"type": "row", "title": row_title, "id": pid, "collapsed": False,
                       "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []})
        pid += 1
        y += 1
        for i, (title, unit, targets) in enumerate(items):
            panels.append({
                "type": "timeseries", "id": pid, "title": title, "datasource": ds,
                "gridPos": {"h": 8, "w": 8, "x": (i % 3) * 8, "y": y + (i // 3) * 8},
                "fieldConfig": {"defaults": {"unit": unit, "min": 0}, "overrides": []},
                "options": {"legend": {"displayMode": "list", "placement": "bottom"}, "tooltip": {"mode": "multi"}},
                "targets": [{"refId": chr(65 + j), "expr": expr, "legendFormat": legend, "datasource": ds}
                            for j, (expr, legend) in enumerate(targets)],
            })
            pid += 1
        y += ((len(items) + 2) // 3) * 8
    return {"uid": "scalability", "title": "Scalability", "schemaVersion": 41, "version": 1, "refresh": "5s",
            "time": {"from": "now-15m", "to": "now"}, "tags": ["scalability"], "panels": panels}


if __name__ == "__main__":
    OUT.write_text(json.dumps(build(), indent=2) + "\n")
    print(f"wrote {OUT}")
