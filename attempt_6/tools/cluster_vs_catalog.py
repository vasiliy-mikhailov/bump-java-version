"""For each missed-intent cluster (from iter-0 only_human), search the recipe catalog
for matches by keyword/phrase overlap on (kind, general_idea, why_exists) vs (name, displayName, description).

Output per (jv_to, top-N clusters):
  - Top 5 catalog recipe candidates ranked by overlap score
  - Whether any catalog hit is already in our current recipe.yaml
"""
import os, json, re, collections, math

CATALOG = "/tmp/recipes_catalog/catalog.json"
INTENT_DIR = "/home/vmihaylov/java_8_11_17_to_java_21/attempt_6/intent_samples"
RECIPE_DIR = "/home/vmihaylov/java_8_11_17_to_java_21/attempt_6/recipe_samples_iter0"  # use iter-0 only_human
RECIPE_YAML = "/home/vmihaylov/java_8_11_17_to_java_21/attempt_6/recipe.yaml"

STOP = set("the a an of to and or in for with on by at as is are was be it its this that from into per via not no but if then else into within without using upgrade update bump set apply add remove replace rename migrate use change make convert insert delete drop fix new old build run java spring boot version 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21".split())


def tokens(s):
    s = (s or "").lower()
    s = re.sub(r"[^a-z0-9]+", " ", s)
    out = []
    for t in s.split():
        if len(t) >= 3 and t not in STOP:
            out.append(t)
    return out


def stage_jv(slug):
    m = re.search(r"__J(\d+)toJ(\d+)$", slug)
    return (int(m.group(1)), int(m.group(2))) if m else (None, None)


def kind_sig(atom):
    k = (atom.get("kind") or "").lower()
    return re.sub(r"[_\d]+$", "", k) or "?"


def load_atoms(base, slug):
    p = os.path.join(base, slug, "breaking.json")
    if not os.path.exists(p): return None
    try: d = json.load(open(p))
    except: return None
    return [a for v in (d.get("by_file") or {}).values() for a in v]


catalog = json.load(open(CATALOG))
# Pre-tokenize catalog
for r in catalog:
    txt = " ".join([r["name"], r.get("displayName", ""), r.get("description", "")])
    r["_toks"] = set(tokens(txt))

# Current recipe.yaml (so we mark already-in-use)
current = {}
if os.path.exists(RECIPE_YAML):
    import yaml as _y
    raw = _y.safe_load(open(RECIPE_YAML)) or {}
    current = {int(k): set(v or []) for k, v in raw.items()}

# Aggregate only_human breaking per jv_to (from iter-0 paired comparison)
per_jv = collections.defaultdict(list)  # jv_to -> list of (atom, signature)
for slug in sorted(os.listdir(INTENT_DIR)):
    jf, jt = stage_jv(slug)
    if jt is None: continue
    h = load_atoms(INTENT_DIR, slug)
    r = load_atoms(RECIPE_DIR, slug)
    if h is None or r is None: continue
    recipe_sigs = {kind_sig(a) for a in r}
    for a in h:
        if kind_sig(a) not in recipe_sigs:
            per_jv[jt].append((a, kind_sig(a)))


def cluster_search(jv_to, atoms_with_sig, top_clusters=8, top_recipes=4):
    sig_examples = collections.defaultdict(list)
    for atom, sig in atoms_with_sig:
        sig_examples[sig].append(atom)
    sorted_clusters = sorted(sig_examples.items(), key=lambda x: -len(x[1]))[:top_clusters]
    print(f"\n=== jv_to=J{jv_to} ({len(atoms_with_sig)} missed atoms, top {len(sorted_clusters)} clusters) ===")
    cur = current.get(jv_to, set())

    for sig, items in sorted_clusters:
        # Build query token set from kind + general_idea + why_exists of cluster members
        toks = set()
        for it in items[:10]:
            toks |= set(tokens((it.get("kind") or "") + " " + (it.get("general_idea") or "") + " " + (it.get("why_exists") or "")))
        # Rank catalog by overlap (Jaccard-ish but biased by query)
        scored = []
        for rec in catalog:
            inter = len(toks & rec["_toks"])
            if inter == 0: continue
            denom = math.log(1 + len(rec["_toks"]) + len(toks))
            scored.append((inter / denom, rec))
        scored.sort(key=lambda x: -x[0])
        print(f"\n  [{len(items)}x] {sig}  e.g.: {(items[0].get('general_idea') or '')[:120]}")
        if not scored:
            print(f"    no catalog match")
            continue
        for score, rec in scored[:top_recipes]:
            in_use = " (already in recipe.yaml)" if rec["name"] in cur else ""
            print(f"    {score:5.2f}  {rec['name']}{in_use}")
            if rec.get("description"):
                print(f"           {rec['description'][:140]}")


for jv_to in (11, 17, 21):
    if jv_to in per_jv:
        cluster_search(jv_to, per_jv[jv_to], top_clusters=6, top_recipes=4)
