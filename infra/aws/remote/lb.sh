#!/bin/bash
# Nginx round-robin over the app hosts. Usage: lb.sh <app_ip> [<app_ip> ...]
set -euo pipefail
mkdir -p /etc/sns-lb
{
  echo "worker_processes auto;"
  echo "worker_rlimit_nofile 1048576;"
  echo "events { worker_connections 65535; }"
  echo "http {"
  echo "  access_log off;"
  echo "  upstream app {"
  for ip in "$@"; do echo "    server ${ip}:8080;"; done
  echo "    keepalive 1024;"
  echo "  }"
  echo "  server {"
  echo "    listen 80 backlog=65535;"
  echo "    location / { proxy_pass http://app; proxy_http_version 1.1; proxy_set_header Connection \"\"; }"
  echo "    location = /nginx_status { stub_status; }"
  echo "  }"
  echo "}"
} > /etc/sns-lb/nginx.conf
docker rm -f lb >/dev/null 2>&1 || true
docker run -d --name lb --network host --restart unless-stopped --ulimit nofile=1048576:1048576 \
  -v /etc/sns-lb/nginx.conf:/etc/nginx/nginx.conf:ro nginx:1.28 >/dev/null
sleep 1; curl -sf -o /dev/null http://127.0.0.1/nginx_status && echo "lb ready ($# apps)"
