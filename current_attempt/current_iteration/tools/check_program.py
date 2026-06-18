#!/usr/bin/env python3
"""Static anti-cheat gate: validate a conversion program against the grammar + catalog.
Every op must be {recipe with FQN in the catalog} or {intent in the allow-list}. Nothing else is
expressible (no hand-edit / skipTests / test-deletion), so this is the structural purity check.
Usage: check_program.py <program.json> <catalog/recipes.txt>   -> prints OK or VIOLATION lines; exit 0/1
"""
import sys, json
prog = json.load(open(sys.argv[1])).get("program", [])
catalog = {l.strip() for l in open(sys.argv[2]) if l.strip() and not l.startswith("#")}
INTENTS = {"set_target", "bump_wrapper"}   # operator-gated allow-list
ENVS = {"from", "to"}
v = []
if not isinstance(prog, list) or not prog:
    v.append("program must be a non-empty list")
for i, op in enumerate(prog):
    if not isinstance(op, dict) or "op" not in op:
        v.append(f"[{i}] not an op object"); continue
    if op["op"] == "recipe":
        if op.get("fqn") not in catalog: v.append(f"[{i}] recipe FQN not in catalog: {op.get('fqn')}")
        if op.get("env", "to") not in ENVS: v.append(f"[{i}] bad env: {op.get('env')}")
    elif op["op"] == "intent":
        if op.get("name") not in INTENTS: v.append(f"[{i}] intent not in allow-list: {op.get('name')}")
    else:
        v.append(f"[{i}] illegal op type '{op['op']}' (only recipe|intent — no hand-edits)")
if v:
    print("\n".join("VIOLATION " + x for x in v)); sys.exit(1)
print("OK"); sys.exit(0)
