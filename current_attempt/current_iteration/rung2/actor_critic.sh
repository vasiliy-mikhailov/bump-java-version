#!/bin/bash
# actor_critic.sh -- the rung-2 workflow named as an explicit actor-critic loop.
#
#   ACTOR  (rung2_host.sh): clone @sha -> baseline under jv_from (pre_set) -> the agent migrates -> emits the
#           ACTION into $O: rewrite.yml (program), agent.log (file actions), agent.diff, and the migrated
#           workspace built + tested under jv_to (compile_rc, test_rc).
#   CRITIC (critic.py): reward = gate_pass x 0.9^(parametric_recipes + manual_edits), 0 if the gate fails or
#           the action cheated. A pure composition of score.py (gate) + check_program.py (parametric) +
#           r2score_one.py (edits/cheat); no second reward definition.
#
# rung2_one_scored.sh already runs this loop end to end (actor = rung2_host, critic = score + r2score_one).
# critic.py CONSOLIDATES the critic into one deterministic, unit-tested unit (see smoke_actor_critic.py). This
# wrapper simply runs the critic over an action the actor already produced -- handy for re-scoring or testing.
#
# Usage: actor_critic.sh <O_dir> <workspace> <pre_set> <from> <to> <comprc> <testrc> <cwe_json> [repo]
set -euo pipefail
CI=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$CI/rung2/critic.py" "$@"
