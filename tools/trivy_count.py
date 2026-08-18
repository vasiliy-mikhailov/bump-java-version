#!/usr/bin/env python3
"""Count CRITICAL+HIGH vulnerabilities per MODULE from a trivy fs JSON report.

Trivy groups findings by Target (the manifest that declared the dependency), e.g.
"service-a/pom.xml" or "build.gradle.kts", which maps directly onto the harness's
module model. Usage:
    trivy_count.py <trivy.json>            -> summary line + per-module JSON on stdout
Exit 0 always; emits {"total": -1} when the report is unusable so the scorer can
tell "no vulnerabilities" apart from "could not scan" (never 0.99**-1).
"""
import json
import os
import sys


def module_of(target: str) -> str:
    """Map a trivy Target (a manifest path) to a module key: its directory, or '.' for root."""
    d = os.path.dirname(target or "")
    return d if d else "."


def count(path):
    try:
        with open(path) as f:
            rep = json.load(f)
    except Exception as e:
        return {"total": -1, "by_module": {}, "note": f"unreadable: {e.__class__.__name__}"}
    results = rep.get("Results")
    if results is None:
        return {"total": -1, "by_module": {}, "note": "no Results key"}

    by_module, seen = {}, {}
    for r in results:
        tgt = r.get("Target", "")
        mod = module_of(tgt)
        vulns = r.get("Vulnerabilities") or []
        for v in vulns:
            sev = (v.get("Severity") or "").upper()
            if sev not in ("CRITICAL", "HIGH"):
                continue
            # dedupe within a module: the same CVE on the same package counts once,
            # so a dependency pulled twice into one module is not double charged.
            key = (mod, v.get("VulnerabilityID"), v.get("PkgName"), v.get("InstalledVersion"))
            if key in seen:
                continue
            seen[key] = True
            by_module.setdefault(mod, {"critical": 0, "high": 0})
            by_module[mod]["critical" if sev == "CRITICAL" else "high"] += 1

    total = sum(m["critical"] + m["high"] for m in by_module.values())
    return {"total": total, "by_module": by_module, "modules_with_findings": len(by_module)}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(json.dumps({"total": -1, "note": "usage: trivy_count.py <trivy.json>"}))
        sys.exit(0)
    print(json.dumps(count(sys.argv[1])))
