import json, glob, os
from collections import defaultdict

passes = defaultdict(list)
for fp in glob.glob('attempt_2/verify/baseline/*/metrics.json'):
    d = json.load(open(fp))
    if not d.get('build_pass'): continue
    k = (d['java_version'], d['dep_family'])
    passes[k].append(d)

for k in passes:
    passes[k].sort(key=lambda r: r.get('clone_elapsed_s', 999))

dataset = []
for k in sorted(passes):
    for i, d in enumerate(passes[k][:8], 1):
        repo = d['repo']
        owner, name = repo.split('/', 1)
        dataset.append({
            'id': f'{k[1]}__j{k[0]}__{i}',
            'java_version': k[0],
            'dep_family': k[1],
            'repo_full_name': repo,
            'owner': owner,
            'repo': name,
            'clone_url': f'https://github.com/{repo}.git',
            'html_url': f'https://github.com/{repo}',
            'commit_sha': d['commit_sha'],
            'build_tool': d.get('build_tool', 'maven'),
            'baseline_build_pass': True,
            'baseline_build_elapsed_s': d.get('build_elapsed_s', 0),
            'baseline_clone_elapsed_s': d.get('clone_elapsed_s', 0),
        })

json.dump(dataset, open('attempt_2/java21-migration-dataset.json', 'w'), indent=2)
print(f'wrote {len(dataset)} entries')
per_cell = defaultdict(int)
for e in dataset: per_cell[(e['java_version'], e['dep_family'])] += 1
print()
print('java | family             | entries')
for k in sorted(per_cell):
    print(f'{k[0]:>4} | {k[1]:<19} | {per_cell[k]}')
