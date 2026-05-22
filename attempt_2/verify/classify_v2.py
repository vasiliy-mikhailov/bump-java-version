"""
v2 classifier — richer Java version detection, caches the build-file text on disk
so future detector tweaks don't need to re-fetch from GitHub.

Input:
  attempt_2/discover/candidates.json  (round-1 repos)
  attempt_2/discover/raw/code-*.json  (round-2 code search hits)

Output:
  attempt_2/verify/poms_cache/<owner>__<repo>__<branch>.txt   (build file text)
  attempt_2/verify/classified_v2.json                          (classification)
"""
import json, re, os, base64
from concurrent.futures import ThreadPoolExecutor, as_completed
from subprocess import run, PIPE
import threading

CACHE = 'attempt_2/verify/poms_cache'
os.makedirs(CACHE, exist_ok=True)

# 1. Round-1 candidate dicts
candidates = {}
for r in json.load(open('attempt_2/discover/candidates.json')):
    candidates[r['full_name']] = r

# 2. Round-2 hits (code search) — add new repos by full_name
import glob
for f in sorted(glob.glob('attempt_2/discover/raw/code-*.json')):
    try:
        data = json.load(open(f))
    except Exception:
        continue
    for item in data.get('items', []):
        rrepo = item.get('repository', {})
        full = rrepo.get('full_name')
        if not full:
            continue
        if full not in candidates:
            candidates[full] = {
                'full_name': full,
                'owner': rrepo['owner']['login'],
                'repo': rrepo['name'],
                'default_branch': rrepo.get('default_branch','main'),
                'size_kb': 0,  # unknown from code search; we'll fetch later
                'stars': 0,
                'html_url': rrepo['html_url'],
                'description': '',
                'families_from_search': [],
                'from_round2': True,
            }
print(f'total candidates (round1+2 union): {len(candidates)}')

def gh_api(path):
    p = run(['gh','api',path], stdout=PIPE, stderr=PIPE)
    if p.returncode != 0: return None
    try: return json.loads(p.stdout)
    except Exception: return None

def fetch_text(owner, repo, path, ref):
    d = gh_api(f'/repos/{owner}/{repo}/contents/{path}?ref={ref}')
    if not d or 'content' not in d: return None
    try: return base64.b64decode(d['content']).decode('utf-8','replace')
    except Exception: return None

def cache_path(full, branch):
    return os.path.join(CACHE, full.replace('/','__') + '__' + branch.replace('/','_') + '.txt')

# Java version detector v2 — handles release, properties cross-refs, gradle styles
JV_PATTERNS = [
    re.compile(r'<java\.version>\s*1?\.?(\d+)\s*</java\.version>', re.I),
    re.compile(r'<maven\.compiler\.source>\s*1?\.?(\d+)\s*</maven\.compiler\.source>', re.I),
    re.compile(r'<maven\.compiler\.target>\s*1?\.?(\d+)\s*</maven\.compiler\.target>', re.I),
    re.compile(r'<maven\.compiler\.release>\s*(\d+)\s*</maven\.compiler\.release>', re.I),
    re.compile(r'<source>\s*1?\.?(\d+)\s*</source>', re.I),
    re.compile(r'<target>\s*1?\.?(\d+)\s*</target>', re.I),
    re.compile(r'<release>\s*(\d+)\s*</release>', re.I),
    re.compile(r'<jdk\.version>\s*1?\.?(\d+)\s*</jdk\.version>', re.I),
    re.compile(r'<jdk>\s*1?\.?(\d+)\s*</jdk>', re.I),
    re.compile(r'<java-version>\s*1?\.?(\d+)\s*</java-version>', re.I),
    re.compile(r'<javaVersion>\s*1?\.?(\d+)\s*</javaVersion>'),
    # gradle
    re.compile(r'sourceCompatibility\s*=?\s*["\x27]?(?:JavaVersion\.VERSION_)?1?[._]?(\d+)', re.I),
    re.compile(r'targetCompatibility\s*=?\s*["\x27]?(?:JavaVersion\.VERSION_)?1?[._]?(\d+)', re.I),
    re.compile(r'languageVersion\s*=\s*JavaLanguageVersion\.of\((\d+)\)', re.I),
    re.compile(r'JavaVersion\.VERSION_(\d+)', re.I),
]
PROP_REF = re.compile(r'<java\.version>\s*\${([^}]+)}\s*</java\.version>', re.I)

def detect_jv(text):
    cands = []
    for p in JV_PATTERNS:
        for m in p.finditer(text):
            try:
                v = int(m.group(1))
                if v == 1: continue
                if 7 <= v <= 25: cands.append(v)
            except Exception: pass
    # Resolve property reference if present
    m = PROP_REF.search(text)
    if m:
        prop = m.group(1)
        # Look for <prop>value</prop> elsewhere in same pom
        rx = re.compile(rf'<{re.escape(prop)}>\s*1?\.?(\d+)\s*</{re.escape(prop)}>', re.I)
        for mm in rx.finditer(text):
            try:
                v = int(mm.group(1))
                if 7 <= v <= 25: cands.append(v)
            except Exception: pass
    if not cands: return None
    # Take the most common
    from collections import Counter
    return Counter(cands).most_common(1)[0][0]

# Family signatures
SIGS = {
    'spring-boot-2': [
        re.compile(r'spring-boot-starter-parent.{0,200}<version>\s*2\.\d', re.S),
        re.compile(r'<spring-boot\.version>\s*2\.', re.I),
        re.compile(r'org\.springframework\.boot[":\x27 ]+2\.\d', re.I),
    ],
    'jakarta-ee-javax': [
        re.compile(r'<groupId>\s*javax\.persistence\s*</groupId>', re.I),
        re.compile(r'<groupId>\s*javax\.servlet\s*</groupId>', re.I),
        re.compile(r'<groupId>\s*javax\.ws\.rs\s*</groupId>', re.I),
        re.compile(r'<groupId>\s*javax\.validation\s*</groupId>', re.I),
        re.compile(r'javax\.persistence-api', re.I),
    ],
    'junit4-mockito': [
        re.compile(r'<groupId>\s*junit\s*</groupId>\s*<artifactId>\s*junit\s*</artifactId>\s*<version>\s*4\.', re.S|re.I),
        re.compile(r'<artifactId>\s*mockito-core\s*</artifactId>\s*<version>\s*[1-4]\.', re.S|re.I),
        re.compile(r'mockito-all', re.I),
    ],
    'hibernate-5': [
        re.compile(r'<artifactId>\s*hibernate-core\s*</artifactId>\s*<version>\s*5\.', re.S|re.I),
        re.compile(r'hibernate-entitymanager', re.I),
    ],
}
def evidence(text):
    return [f for f, pats in SIGS.items() if any(p.search(text) for p in pats)]

def module_count_of(text):
    return len(re.findall(r'<module>', text))

def classify_one(r):
    owner = r['owner']; repo = r['repo']; branch = r['default_branch']
    cp = cache_path(r['full_name'], branch)
    text = None
    file_kind = None
    if os.path.exists(cp):
        text = open(cp,'r').read()
        file_kind = 'pom' if '<project' in text else 'gradle'
    else:
        text = fetch_text(owner, repo, 'pom.xml', branch)
        if text:
            file_kind = 'pom'
        else:
            text = fetch_text(owner, repo, 'build.gradle', branch) or fetch_text(owner, repo, 'build.gradle.kts', branch)
            file_kind = 'gradle' if text else None
        if text:
            with open(cp, 'w') as fh: fh.write(text)
    if not text: return None
    return {**r, 'build_file': file_kind, 'java_version_declared': detect_jv(text),
            'families_evidenced': evidence(text), 'module_count': module_count_of(text) if file_kind=='pom' else 0}

done = 0; lock = threading.Lock()
out = []
total = len(candidates)
def progress():
    global done
    with lock:
        done += 1
        if done % 100 == 0: print(f'  {done}/{total} processed, {len(out)} classified', flush=True)

with ThreadPoolExecutor(max_workers=10) as ex:
    futs = {ex.submit(classify_one, r): r for r in candidates.values()}
    for f in as_completed(futs):
        progress()
        try: res = f.result()
        except Exception: continue
        if res: out.append(res)

json.dump(out, open('attempt_2/verify/classified_v2.json','w'), indent=2)
print(f'classified: {len(out)} of {total}')
