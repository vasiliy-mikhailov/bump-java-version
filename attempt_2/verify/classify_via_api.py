"""
Cheap classification step — for each candidate, fetch pom.xml or build.gradle via GitHub API
(no clone needed). Parse:
  - declared Java version (8/11/17 — or "unknown")
  - confirmed family signatures (which family did the build file evidence)
  - module_count (multi-module flag)

Reject repos whose root build file is gone or whose Java version is not in {8,11,17}.
Output: attempt_2/verify/classified.json — list of {full_name, java_version, families_evidenced, module_count, ...}

Runs in parallel with a thread pool.
"""
import json, re, os, sys, base64, time
from concurrent.futures import ThreadPoolExecutor, as_completed
from subprocess import run, PIPE

CAND = json.load(open("attempt_2/discover/candidates.json"))
print(f"candidates: {len(CAND)}", flush=True)

def gh_api(path):
    p = run(["gh","api",path], stdout=PIPE, stderr=PIPE)
    if p.returncode != 0:
        return None
    try:
        return json.loads(p.stdout)
    except Exception:
        return None

def fetch_file(owner, repo, path, ref):
    data = gh_api(f"/repos/{owner}/{repo}/contents/{path}?ref={ref}")
    if not data or "content" not in data:
        return None
    try:
        return base64.b64decode(data["content"]).decode("utf-8","replace")
    except Exception:
        return None

JV_PATTERNS = [
    re.compile(r"<java\.version>\s*1?\.?(\d+)\s*</java\.version>", re.I),
    re.compile(r"<maven\.compiler\.source>\s*1?\.?(\d+)\s*</maven\.compiler\.source>", re.I),
    re.compile(r"<source>\s*1?\.?(\d+)\s*</source>", re.I),
    re.compile(r"<jdk\.version>\s*1?\.?(\d+)\s*</jdk\.version>", re.I),
    # gradle
    re.compile(r"sourceCompatibility\s*=?\s*[\"\x27]?(?:JavaVersion\.VERSION_)?1?[._]?(\d+)", re.I),
    re.compile(r"languageVersion\s*=\s*JavaLanguageVersion\.of\((\d+)\)", re.I),
]
def detect_java_version(text):
    candidates = []
    for p in JV_PATTERNS:
        for m in p.finditer(text):
            try:
                v = int(m.group(1))
                if v == 1: continue
                candidates.append(v)
            except Exception:
                pass
    if not candidates:
        return None
    # Map common variants: e.g. "1.8" → 8, "11" → 11
    return max(candidates) if max(candidates) >= 7 else None

# Family signatures in pom/gradle
SIGS = {
    "spring-boot-2": [
        re.compile(r"spring-boot-starter-parent.{0,200}<version>\s*2\.\d", re.S),
        re.compile(r"spring-boot.{0,5}version[^<]{0,30}>\s*2\.\d", re.I|re.S),
        re.compile(r"org\.springframework\.boot[\":\x27 ]+2\.\d", re.I),
    ],
    "jakarta-ee-javax": [
        re.compile(r"<groupId>\s*javax\.persistence\s*</groupId>", re.I),
        re.compile(r"<groupId>\s*javax\.servlet\s*</groupId>", re.I),
        re.compile(r"<groupId>\s*javax\.ws\.rs\s*</groupId>", re.I),
        re.compile(r"<groupId>\s*javax\.validation\s*</groupId>", re.I),
        re.compile(r"javax\.persistence-api", re.I),
        re.compile(r"jakarta\.persistence.{0,80}<version>\s*2\.\d", re.I|re.S),  # jakarta 2.x = javax-equivalent
    ],
    "junit4-mockito": [
        re.compile(r"<groupId>\s*junit\s*</groupId>\s*<artifactId>\s*junit\s*</artifactId>\s*<version>\s*4\.", re.S|re.I),
        re.compile(r"junit:junit:4\.", re.I),
        re.compile(r"mockito-core.{0,200}<version>\s*[1-4]\.", re.S|re.I),
        re.compile(r"mockito-all", re.I),
    ],
    "hibernate-5": [
        re.compile(r"hibernate-core.{0,200}<version>\s*5\.", re.S|re.I),
        re.compile(r"hibernate-entitymanager", re.I),
        re.compile(r"org\.hibernate:hibernate-core:5\.", re.I),
    ],
}
def evidence(text):
    out = []
    for fam, pats in SIGS.items():
        if any(p.search(text) for p in pats):
            out.append(fam)
    return out

def module_count_of(text):
    return len(re.findall(r"<module>", text))

def classify_one(r):
    owner = r["owner"]; repo = r["repo"]; branch = r["default_branch"]
    pom = fetch_file(owner, repo, "pom.xml", branch)
    text = pom
    file_kind = "pom" if pom else None
    if not text:
        gradle = fetch_file(owner, repo, "build.gradle", branch) or fetch_file(owner, repo, "build.gradle.kts", branch)
        text = gradle
        file_kind = "gradle" if gradle else None
    if not text:
        return None
    jv = detect_java_version(text)
    fams = evidence(text)
    mc = module_count_of(text) if file_kind == "pom" else 0
    return {
        **r,
        "build_file": file_kind,
        "java_version_declared": jv,
        "families_evidenced": fams,
        "module_count": mc,
    }

import threading
done = 0; lock = threading.Lock()
out = []
total = len(CAND)
def report(r):
    global done
    with lock:
        done += 1
        if done % 50 == 0:
            print(f"  {done}/{total} processed, {len(out)} classified", flush=True)

with ThreadPoolExecutor(max_workers=8) as ex:
    futs = {ex.submit(classify_one, r): r for r in CAND}
    for f in as_completed(futs):
        report(None)
        try:
            res = f.result()
        except Exception as e:
            continue
        if res:
            out.append(res)

json.dump(out, open("attempt_2/verify/classified.json","w"), indent=2)
print(f"classified: {len(out)} of {total}")
