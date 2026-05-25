"""Pull concrete human breaking intents for the clusters we proposed recipes for,
so we can walk through what each recipe would (or wouldn't) do."""
import os, json, re, collections

INTENT = "/home/vmihaylov/java_8_11_17_to_java_21/attempt_6/intent_samples"

CLUSTERS_OF_INTEREST = {
    21: [
        ("javax_persistence", lambda a: "javax.persistence" in (a.get("before") or "") or "javax.persistence" in (a.get("after") or "")),
        ("javax_validation", lambda a: "javax.validation" in (a.get("before") or "") or "javax.validation" in (a.get("after") or "")),
        ("javax_security", lambda a: "javax.security" in (a.get("before") or "") or "javax.security" in (a.get("after") or "")),
        ("update_import_path", lambda a: (a.get("kind") or "").lower() == "update_import_path"),
        ("update_java_version_in_workflow", lambda a: ".github" in (a.get("general_idea") or "").lower() or "workflow" in (a.get("general_idea") or "").lower()),
        ("add_import_domain", lambda a: (a.get("kind") or "").lower() == "add_import" and "javax" not in (a.get("after") or "") and "jakarta" not in (a.get("after") or "")),
    ],
    17: [
        ("javax_persistence", lambda a: "javax.persistence" in (a.get("before") or "")),
        ("path_matcher", lambda a: "path" in (a.get("kind") or "").lower() and "matcher" in (a.get("kind") or "").lower()),
    ],
    11: [
        ("springbootservletinitializer", lambda a: "SpringBootServletInitializer" in (a.get("before") or "") or "SpringBootServletInitializer" in (a.get("after") or "")),
        ("jaxb_module", lambda a: "jaxb" in (a.get("general_idea") or "").lower() or "java.xml.bind" in (a.get("before") or "")),
    ],
}


def stage_jv(slug):
    m = re.search(r"__J(\d+)toJ(\d+)$", slug)
    return (int(m.group(1)), int(m.group(2))) if m else (None, None)


buckets = {(jv_to, name): [] for jv_to, names in CLUSTERS_OF_INTEREST.items() for name, _ in names}

for slug in sorted(os.listdir(INTENT)):
    jv_from, jv_to = stage_jv(slug)
    if jv_to not in CLUSTERS_OF_INTEREST: continue
    bp = os.path.join(INTENT, slug, "breaking.json")
    if not os.path.exists(bp): continue
    try: d = json.load(open(bp))
    except: continue
    for f, atoms in (d.get("by_file") or {}).items():
        for a in atoms:
            for cluster_name, matcher in CLUSTERS_OF_INTEREST[jv_to]:
                try:
                    if matcher(a):
                        buckets[(jv_to, cluster_name)].append((slug, f, a))
                except Exception:
                    pass

for (jv_to, cname), items in sorted(buckets.items()):
    print(f"\n=== J{jv_to} / {cname} ({len(items)} matches, showing first 3) ===")
    for slug, fname, a in items[:3]:
        print(f"  stage={slug}")
        print(f"  file={fname}")
        print(f"    kind:        {a.get('kind')}")
        print(f"    general_idea: {(a.get('general_idea') or '')[:160]}")
        print(f"    why_exists:  {(a.get('why_exists') or '')[:160]}")
        print(f"    before:      {(a.get('before') or '')[:200]!r}")
        print(f"    after:       {(a.get('after') or '')[:200]!r}")
        print()
