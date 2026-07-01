#!/usr/bin/env python3
"""Build the per-module iteration STAGE prompt for a HETEROGENEOUS repo (modules on different LTS hops).
The agent runs once per repo and bumps EACH module to its OWN next-LTS target. Emits the stage header +
per-module plan + the relevant hop skills (concatenated, frontmatter stripped) on stdout.
Usage: multihop_stage.py <modules.jsonl> <skills_dir> <build_jdk> <baseline_jdk>
"""
import sys, json, os, re

mf, skdir, BUILD_JDK, BASE_JDK = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

mods = []
for l in open(mf):
    l = l.strip()
    if not l or '"summary"' in l or '"module"' not in l:
        continue
    try:
        d = json.loads(l)
    except Exception:
        continue
    if d.get("bumpable") and isinstance(d.get("to"), int) and isinstance(d.get("from"), int):
        mods.append(d)

plan = "\n".join(f'  - `{d["module"]}`  ({d["tool"]})  Java {d["from"]} -> {d["to"]}' for d in mods)
hops = sorted({(d["from"], d["to"]) for d in mods})
jdk_list = ", ".join(f"/opt/jdk/{n}" for n in sorted({8, 11, 17, 21, int(BUILD_JDK)}))

def strip_frontmatter(t):
    return re.sub(r'^---.*?^---[ \t]*\n', '', t, count=1, flags=re.S | re.M)

sections = []
for f, t in hops:
    p = os.path.join(skdir, f"bump-java-{f}-to-{t}", "SKILL.md")
    if os.path.exists(p):
        sections.append(f"########## HOP {f} -> {t} ##########\n" + strip_frontmatter(open(p, errors="ignore").read()).strip())
skills = "\n\n".join(sections)

stage = f"""STAGE -- the project in /work is a MULTI-MODULE build whose modules sit at DIFFERENT Java levels. \
Bump EACH module to its OWN next-LTS target, exactly as listed in the per-module plan below. A module that is \
already at or above its target needs no work; never lower a module. The gate builds the whole reactor under \
JDK {BUILD_JDK} (so every module can compile to its own release) and it scores EACH module against its own \
target: a single module left below its target fails the whole repo, so do not leave any module behind.

Per-module plan (module : build tool : from -> to):
{plan}

Method -- iterate the plan module by module. For each module:
  1. set THAT module's effective compiler target to its `to` -- Maven: the module's own pom `<release>` (or \
`maven.compiler.source`/`target`/`release`, single source of truth per pom); Gradle: that module's build file \
(`JavaLanguageVersion.of`, `sourceCompatibility`/`targetCompatibility`, `options.release`, `jvmToolchain`).
  2. floor that hop's JDK-pinned deps (Lombok / ByteBuddy / Mockito / JaCoCo) and apply that hop's OpenRewrite \
recipes to that module -- Maven: `-pl <module>` (add `-am` only if the recipe needs upstream modules); Gradle: \
scope the change to that module's build file.
Modules on the same hop can be handled together. The available JDKs are at {jdk_list}. Builds are NOT \
time-boxed -- let cold builds finish.

After every module in the plan has been bumped to its own target, build and test the whole reactor under \
JDK {BUILD_JDK} and confirm every previously-passing test still passes.

The hop guidance for each hop present in this repo follows. Apply the section that matches each module's hop:

{skills}"""
print(stage)
