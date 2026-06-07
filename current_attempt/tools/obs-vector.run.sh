#!/usr/bin/env bash
# P10 frog's eye capture layer: (re)create the obs-vector container reproducibly.
# Vector file-globs /var/log/**/* + /var/lib/docker/containers (all host + container logs)
# and host_metrics, sinks JSONL to /var/log/observe for the compactor.
set -e
docker rm -f obs-vector >/dev/null 2>&1 || true
mkdir -p /home/vmihaylov/vector-data
docker run -d --name obs-vector --restart unless-stopped \
  -e VECTOR_LOG=warn \
  -v /home/vmihaylov/java_8_11_17_to_java_21/current_attempt/tools/vector.toml:/etc/vector/vector.toml:ro \
  -v /var/log:/var/log:ro \
  -v /var/log/observe:/var/log/observe:rw \
  -v /var/lib/docker/containers:/var/lib/docker/containers:ro \
  -v /home/vmihaylov/vector-data:/var/lib/vector \
  timberio/vector:0.39.0-alpine --config /etc/vector/vector.toml
echo "obs-vector (re)created"
