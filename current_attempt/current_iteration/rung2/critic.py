#!/usr/bin/env python3
"""critic.py -- the CRITIC of the rung-2 actor-critic loop.

ACTOR  = the migration agent (OpenHands+Qwen, driven by rung2_host.sh): clones the repo @sha, baselines it
         under jv_from (pre_set = tests passing before), edits the project, and emits an ACTION into $O:
         rewrite.yml (its OpenRewrite program), agent.log (its file actions), agent.diff (the git diff), and
         the migrated workspace built + tested under jv_to.
CRITIC = this. value(action) = gate_pass x 0.9^(parametric_recipes + manual_edits), 0 if the gate fails or the
         action cheated. It is a pure, deterministic COMPOSITION of the three scorers already verified in the
         harness; it defines NO second reward:
           score.py final    -> the combined gate (builds under jv_to? every pre-pass test conserved?
                                 effective bytecode target == jv_to?)  -> verdict
           check_program.py   -> count of model-chosen parametric recipes (free hop-fixed intents credited at 0)
           r2score_one.py     -> count of penalized manual edits + cheat detection -> reward
The action is assumed already BUILT+TESTED under jv_to (jvmjob does that in the harness and hands the compile/
test return codes in); the critic runs no docker/build itself, so it is unit-testable in python:3-slim.

CLI: critic.py <O_dir> <workspace> <pre_set> <from> <to> <comprc> <testrc> <cwe_json> [repo]  -> prints reward JSON
"""
import os, sys, subprocess, json

HERE  = os.path.dirname(os.path.abspath(__file__))
SCORE = os.path.join(HERE, "..", "tools", "score.py")
CHECK = os.path.join(HERE, "..", "tools", "check_program.py")
R2    = os.path.join(HERE, "r2score_one.py")


def critic(O, ws, pre_set, frm, to, comprc, testrc, cwe, repo=""):
    py = sys.executable
    # 1. GATE: value the migration outcome. Writes result.json/post_set.txt into O; the VERDICT line is on
    #    stdout, which the harness tees to verdict.txt -- do the same so r2score_one can read it.
    g = subprocess.run([py, SCORE, "final", ws, pre_set, str(frm), str(to), str(comprc), str(testrc), cwe, O],
                       capture_output=True, text=True)
    open(os.path.join(O, "verdict.txt"), "w").write(g.stdout)
    # 2. PENALTY input A -- parametric recipes (free hop-fixed intents excluded by check_program.is_free).
    yml, param = os.path.join(O, "rewrite.yml"), 0
    if os.path.exists(yml):
        c = subprocess.run([py, CHECK, yml, "x", str(to)], capture_output=True, text=True)
        for tok in (c.stdout + " " + c.stderr).split():
            if tok.startswith("PARAMETRIC="):
                param = int(tok.split("=", 1)[1])
    open(os.path.join(O, "param.txt"), "w").write(str(param))
    # 3. PENALTY input B + cheat detection + the final reward (writes score.json).
    subprocess.run([py, R2, O, repo], capture_output=True, text=True)
    return json.loads(open(os.path.join(O, "score.json")).read())


if __name__ == "__main__":
    if len(sys.argv) < 9:
        sys.exit("usage: critic.py <O_dir> <workspace> <pre_set> <from> <to> <comprc> <testrc> <cwe_json> [repo]")
    O, ws, pre, frm, to, cr, tr, cwe = sys.argv[1:9]
    repo = sys.argv[9] if len(sys.argv) > 9 else ""
    print(json.dumps(critic(O, ws, pre, int(frm), int(to), int(cr), int(tr), cwe, repo)))
