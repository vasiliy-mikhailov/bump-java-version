"""Rebuild the dig's remaining-candidates list: all discovered candidates MINUS repos already in
the store (resume-safe; run before each dig chunk). Paths from _paths."""
import json, random
import _paths as P
store = set()
try:
    for line in open(P.STORE):
        try: store.add(json.loads(line)["repo"])
        except Exception: pass
except FileNotFoundError:
    pass
allc = [r.strip() for r in open(P.CANDIDATES) if r.strip()]
rem = [r for r in allc if r not in store]
random.shuffle(rem)
open(P.REMAINING, "w").write("\n".join(rem))
print(f"remaining: {len(rem)} already-dug repos: {len(store)}")
