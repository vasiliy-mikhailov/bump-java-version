"""
History-walker. For each candidate repo (already classified, no java-version filter),
shallow-clone, walk git log of pom.xml, and find:
  - latest commit where pom showed <java.version>11 (or compiler.release=11) AND any family signature
  - latest commit where pom showed <java.version>17 AND any family signature

Emit attempt_2/verify/history_hits.json with one entry per (repo, target_java_version) found.
"""
import json, os, re, subprocess, sys, tempfile, shutil, time
from concurrent.futures import ThreadPoolExecutor, as_completed
import threading

# Pool: all 1823 classified_v2 repos (regardless of declared java version)
POOL = json.load(open('attempt_2/verify/classified_v2.json'))
print(f'pool: {len(POOL)}', flush=True)

WORK = 'attempt_2/verify/clones_history'
os.makedirs(WORK, exist_ok=True)
HITS = 'attempt_2/verify/history_hits.json'

# We look for both target versions
TARGETS = [11, 17]

JV_RX = {
  11: [re.compile(r'<java\.version>\s*(?:1\.)?11\s*</java\.version>'),
       re.compile(r'<maven\.compiler\.(?:source|target|release)>\s*(?:1\.)?11\s*</'),
       re.compile(r'<release>\s*11\s*</release>')],
  17: [re.compile(r'<java\.version>\s*17\s*</java\.version>'),
       re.compile(r'<maven\.compiler\.(?:source|target|release)>\s*17\s*</'),
       re.compile(r'<release>\s*17\s*</release>')],
}

FAM_RX = {
  'spring-boot-2': re.compile(r'spring-boot-starter-parent.{0,200}<version>\s*2\.\d', re.S),
  'jakarta-ee-javax': re.compile(r'<groupId>\s*javax\.(persistence|servlet|ws\.rs|validation)\s*</groupId>', re.I),
  'junit4-mockito': re.compile(r'<groupId>\s*junit\s*</groupId>\s*<artifactId>\s*junit\s*</artifactId>\s*<version>\s*4\.', re.S|re.I),
  'hibernate-5': re.compile(r'<artifactId>\s*hibernate-core\s*</artifactId>\s*<version>\s*5\.', re.S|re.I),
}

def sh(cmd, cwd=None, timeout=60):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, timeout=timeout)

def walk_one(r):
    full = r['full_name']
    safe = full.replace('/','__')
    repo_dir = os.path.join(WORK, safe)
    found = []
    try:
        if not os.path.isdir(repo_dir):
            p = sh(['git','clone','--filter=blob:none','--no-checkout','--depth','300',
                    f'https://github.com/{full}.git', repo_dir], timeout=90)
            if p.returncode != 0:
                return None
        # List all commits that changed pom.xml (or any pom in repo)
        log = sh(['git','log','--all','--pretty=format:%H','--','pom.xml'], cwd=repo_dir, timeout=30)
        commits = log.stdout.decode('utf-8','replace').split()
        if not commits:
            # Try finding a pom anywhere
            ls = sh(['git','ls-tree','-r','HEAD','--name-only'], cwd=repo_dir, timeout=20)
            paths = [p for p in ls.stdout.decode('utf-8','replace').split() if p.endswith('pom.xml')]
            if not paths: return None
            log = sh(['git','log','--all','--pretty=format:%H','--'] + paths[:1], cwd=repo_dir, timeout=30)
            commits = log.stdout.decode('utf-8','replace').split()
            if not commits: return None

        # For each target version, find the LATEST commit where pom matched it AND a family
        for target in TARGETS:
            for c in commits[:60]:  # cap at 60 most-recent commits per repo
                # Fetch pom.xml at this commit
                cat = sh(['git','show', f'{c}:pom.xml'], cwd=repo_dir, timeout=10)
                if cat.returncode != 0: continue
                text = cat.stdout.decode('utf-8','replace')
                if not any(rx.search(text) for rx in JV_RX[target]): continue
                fams = [f for f, rx in FAM_RX.items() if rx.search(text)]
                if not fams: continue
                found.append({
                    'full_name': full,
                    'owner': r['owner'],
                    'repo': r['repo'],
                    'commit_sha': c,
                    'java_version': target,
                    'families_at_commit': fams,
                    'pom_path': 'pom.xml',
                    'size_kb': r.get('size_kb', 0),
                    'html_url': r.get('html_url'),
                })
                break  # latest match for this target is enough
    except subprocess.TimeoutExpired:
        return found if found else None
    except Exception:
        return found if found else None
    return found if found else None

# Save partial results periodically
all_hits = []
done = 0; lock = threading.Lock()
total = len(POOL)
def save():
    json.dump(all_hits, open(HITS, 'w'), indent=2)

with ThreadPoolExecutor(max_workers=8) as ex:
    futs = {ex.submit(walk_one, r): r for r in POOL}
    for f in as_completed(futs):
        with lock:
            done += 1
        try:
            res = f.result(timeout=180)
        except Exception:
            res = None
        if res:
            with lock:
                all_hits.extend(res)
        if done % 50 == 0:
            with lock:
                print(f'  {done}/{total} repos walked, {len(all_hits)} (repo,version) hits', flush=True)
                save()

save()
print(f'done. {done}/{total} repos walked, {len(all_hits)} (repo,version) hits')
