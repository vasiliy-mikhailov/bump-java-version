"""Extract the OpenRewrite recipe catalog from the four downloaded JARs.

For each YAML-declared recipe: name, displayName, description.
For each class-declared recipe: class name only (no description without parsing Java).

Output: /tmp/recipes_catalog/catalog.json — flat list of {source_jar, name, displayName, description}.
"""
import os, json, zipfile, re

JAR_DIR = "/tmp/recipes_catalog"
OUT = "/tmp/recipes_catalog/catalog.json"

entries = []

for fn in sorted(os.listdir(JAR_DIR)):
    if not fn.endswith(".jar"): continue
    jar_path = os.path.join(JAR_DIR, fn)
    with zipfile.ZipFile(jar_path) as z:
        # YAML-defined recipes under META-INF/rewrite/*.yml (skip attribution/)
        for n in z.namelist():
            if not n.startswith("META-INF/rewrite/"): continue
            if "/attribution/" in n: continue
            if not n.endswith((".yml", ".yaml")): continue
            try:
                txt = z.read(n).decode(errors="replace")
            except Exception:
                continue
            # Crude multi-doc parser — split on '\n---' marker
            for doc in re.split(r"(?m)^---\s*$", txt):
                if "name:" not in doc: continue
                name = displayName = description = ""
                # name (required)
                m = re.search(r"(?m)^name:\s*(.+?)\s*$", doc)
                if m: name = m.group(1).strip()
                m = re.search(r"(?m)^displayName:\s*(.+?)\s*$", doc)
                if m: displayName = m.group(1).strip().strip('"\'')
                # description: can be multi-line with > or |
                m = re.search(r"(?m)^description:\s*(.*?)(?=^\w+:\s*$|^---|\Z)", doc, re.DOTALL)
                if m:
                    raw = m.group(1).strip()
                    description = re.sub(r"\s+", " ", raw)[:400]
                if name:
                    entries.append({"source_jar": fn, "name": name,
                                    "displayName": displayName, "description": description})

print(f"extracted {len(entries)} YAML-defined recipes")
# Dedupe by name
seen = {}
for e in entries:
    seen[e["name"]] = e  # keep last (later-listed wins)
unique = list(seen.values())
print(f"deduped: {len(unique)}")
json.dump(unique, open(OUT, "w"), indent=2)
print(f"wrote {OUT}")

# Quick stats per source jar
from collections import Counter
c = Counter(e["source_jar"] for e in unique)
for k, v in c.most_common():
    print(f"  {k}: {v} recipes")
