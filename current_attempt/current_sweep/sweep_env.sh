# Inner-loop (sweep / iteration) path config. Source me:
#   . "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sweep_env.sh"
# Derives SWEEP from this file's OWN location. All the sweep's OWN files live under $SWEEP.
# Only two refs reach OUT of current_sweep -- the outer-loop INPUTS the iteration consumes:
#   $STORE (the dig's baseline corpus) and the skill under $ATTEMPT/.agents/skills.
_d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export SWEEP="$_d"
export ATTEMPT="$(dirname "$SWEEP")"          # outer ralph loop
export ROOT="$(dirname "$ATTEMPT")"
export DATASETS="$SWEEP/datasets"
export DATASET_B="$DATASETS/dataset-shas-B.json"
export OUT="$SWEEP/out"
export LOGS="$SWEEP/logs"
export STORE="$ATTEMPT/corpus/baselines_peryear.json.jsonl"   # OUTER INPUT (dig corpus)
export PATH="$HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
mkdir -p "$DATASETS" "$OUT" "$LOGS" 2>/dev/null || true
