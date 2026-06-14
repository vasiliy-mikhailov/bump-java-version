# Single source of truth for dig + loop-B SHELL paths. Source me:
#   . "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/env.sh"
# Derives ATTEMPT from this file's OWN location -- no hardcoded /home or attempt_N.
_d="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ATTEMPT="$_d"
export ROOT="$(dirname "$ATTEMPT")"
export TOOLS="$ATTEMPT/tools"
export PORTABILITY="$ATTEMPT/portability"
export CORPUS="$ATTEMPT/corpus"
export STORE="$CORPUS/baselines_peryear.json.jsonl"   # dig store (jsonl, append-only)
export STORE_BASE="$CORPUS/baselines_peryear.json"    # sample_shas --out base (.jsonl appended)
export CANDIDATES="$CORPUS/discovered/all_candidates.txt"
export REMAINING="$CORPUS/dig_remaining.txt"
export DATASETS="$CORPUS/datasets"
export DATASET_B="$DATASETS/dataset-shas-B.json"
export SWEEP_OUT="$ATTEMPT/sweep_out"
export LOGS="$ATTEMPT/logs"                            # archived/historical logs
export OBS="/var/log/observe/app"                      # LIVE logs (frog's-eye P10; Vector globs /var/log/**)
export PATH="$HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
mkdir -p "$LOGS" "$SWEEP_OUT" "$DATASETS" 2>/dev/null || true
