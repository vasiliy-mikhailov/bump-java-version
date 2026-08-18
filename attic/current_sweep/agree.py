#!/usr/bin/env python3
"""Cross-agent verdict agreement table over the sweep's out/. Paths from sibling _paths."""
import json, glob, os
import _paths as P
agents = ["opencode", "kilo", "openhands"]
data = {}
for a in agents:
    for d in glob.glob(f"{P.OUT}/sweep3_{a}/*/"):
        rp = d + "result.json"
        if not os.path.exists(rp): continue
        r = json.load(open(rp)); data.setdefault(r["repo"] + " [" + r["hop"] + "]", {})[a] = r["verdict"]
print("%-50s %-11s %-11s %-11s" % ("repo", "opencode", "kilo", "openhands"))
for repo in sorted(data):
    v = data[repo]; cells = "  ".join("%-10s" % (v.get(a, "-")[:10]) for a in agents)
    verds = set(v.get(a) for a in agents)
    flag = "   <-- DISAGREE" if len(verds) > 1 else ("   <-- all FAIL" if "PASS" not in verds else "")
    print("%-50s %s%s" % (repo[:50], cells, flag))
