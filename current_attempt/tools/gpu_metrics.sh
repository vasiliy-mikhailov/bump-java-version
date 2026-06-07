#!/usr/bin/env bash
# Host GPU metrics -> /var/log/observe/app (the frog's eye captures it via the /var/log glob).
# Self-keepalive loop (nvidia-smi every 30s); host_metrics already covers cpu/mem/drive/load.
OUT=/var/log/observe/app/gpu.log
while true; do
  ts=$(date -u +%FT%TZ)
  nvidia-smi --query-gpu=index,name,utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw \
    --format=csv,noheader,nounits 2>/dev/null | while IFS= read -r line; do
      echo "$ts gpu: $line" >> "$OUT"
    done
  sleep 30
done
