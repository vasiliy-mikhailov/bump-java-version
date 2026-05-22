import json
from collections import defaultdict

HIST = json.load(open('attempt_2/verify/history_hits.json'))
CL   = json.load(open('attempt_2/verify/classified_v2.json'))

size_of = {r['full_name']: r.get('size_kb', 0) or 0 for r in CL}
modcount_of = {r['full_name']: r.get('module_count', 0) for r in CL}

pool = defaultdict(list)

for h in HIST:
    j = h['java_version']
    for fam in h['families_at_commit']:
        pool[(j, fam)].append({
            'java_version': j, 'family': fam,
            'full_name': h['full_name'], 'owner': h['owner'], 'repo': h['repo'],
            'commit_sha': h['commit_sha'],
            'html_url': h.get('html_url'),
            'size_kb': size_of.get(h['full_name'], 0),
            'module_count': modcount_of.get(h['full_name'], 0),
            'source': 'history_walk',
        })

for r in CL:
    if r.get('java_version_declared') != 8: continue
    for fam in r.get('families_evidenced', []):
        pool[(8, fam)].append({
            'java_version': 8, 'family': fam,
            'full_name': r['full_name'], 'owner': r['owner'], 'repo': r['repo'],
            'commit_sha': 'HEAD',
            'html_url': r.get('html_url'),
            'size_kb': r.get('size_kb', 0) or 0,
            'module_count': r.get('module_count', 0),
            'source': 'classified_head',
        })

SELECTED = {}
print('=== selection ===')
print('java | family             | pool | chosen')
for (j, fam), cands in sorted(pool.items()):
    cands_sorted = sorted(cands, key=lambda c: (c['module_count'], c['size_kb'] or 999999))
    seen = set(); chosen = []
    for c in cands_sorted:
        if c['owner'] in seen: continue
        seen.add(c['owner'])
        chosen.append(c)
        if len(chosen) >= 8: break
    SELECTED[(j, fam)] = chosen
    print(f'{j:>4} | {fam:<19} | {len(cands):>4} | {len(chosen)}')

out = []
for (j, fam), entries in SELECTED.items():
    for i, c in enumerate(entries, 1):
        out.append({
            'cell_id': f'{fam}__j{j}__{i}',
            'java_version': j,
            'dep_family': fam,
            'repo_full_name': c['full_name'],
            'owner': c['owner'],
            'repo_name': c['repo'],
            'commit_sha': c['commit_sha'],
            'clone_url': f'https://github.com/{c["full_name"]}.git',
            'html_url': c['html_url'],
            'size_kb': c['size_kb'],
            'module_count': c['module_count'],
            'source': c['source'],
        })

json.dump(out, open('attempt_2/dataset_candidates.json', 'w'), indent=2)
print()
print(f'total selected: {len(out)}')
