#!/usr/bin/env python3
"""Translate a declarative conversion program into shell commands to run in the sealed env.
Reads program.json + from/to/build_tool; emits one line per op:  <env>\t<shell-cmd>
The orchestrator runs each via `bjv <env> run '<cmd>'`. Recipes + intents are ALL harness-applied
(via the rewrite plugin / deterministic recipe), so the generator never edits a file.
Usage: applyprog.py <program.json> <from> <to> <maven|gradle>
"""
import sys, json

prog = json.load(open(sys.argv[1]))["program"]
FROM, TO, BT = sys.argv[2], sys.argv[3], sys.argv[4]

# recipe FQN -> artifact coordinates (the catalog carries this; defaults here)
MIG = "org.openrewrite.recipe:rewrite-migrate-java:3.35.0"
MIG25 = "org.openrewrite.recipe:rewrite-migrate-java:3.36.0"
SPRING = "org.openrewrite.recipe:rewrite-spring:6.31.0"
def artifact(fqn):
    if "migrate.UpgradeBuildToJava25" in fqn or "ForJava25" in fqn: return MIG25
    if ".spring." in fqn: return SPRING
    return MIG
PLUGIN = "6.40.0" if TO != "25" else "6.41.0"
PINNED = {"11": "7.6", "17": "7.6", "21": "8.10.2", "25": "9.0.0"}

def mvn_recipe(fqn, art, extra=""):
    return (f"mvn -B -ntp -U -Denforcer.skip=true org.openrewrite.maven:rewrite-maven-plugin:{PLUGIN}:run "
            f"-Drewrite.activeRecipes={fqn} -Drewrite.recipeArtifactCoordinates={art} {extra}").strip()

def gradle_recipe(fqn, art):
    # init-script so no build edit is needed; repos in BOTH initscript{} and rootProject{}
    init = ("initscript{repositories{gradlePluginPortal();mavenCentral()};"
            "dependencies{classpath(\"org.openrewrite:plugin:latest.release\")}};"
            "rootProject{apply plugin: org.openrewrite.gradle.RewritePlugin;"
            f"dependencies{{rewrite(\"{art}\")}};rewrite{{activeRecipe(\"{fqn}\")}}}}")
    return (f"printf '%s' '{init}' > /tmp/rw.init.gradle && "
            "./gradlew --no-daemon --init-script /tmp/rw.init.gradle rewriteRun")

SETTARGET_YML = ("type: specs.openrewrite.org/v1beta/recipe\\n"
                 "name: com.bjv.SetTarget\\n"
                 "recipeList:\\n"
                 "  - org.openrewrite.java.migrate.UpgradeJavaVersion:\\n"
                 f"      version: {TO}\\n")

def set_target():
    # The "set Java toolchain/release to jv_to" intent. Run in the FROM env: the project still declares the
    # OLD toolchain, which only resolves where jv_from exists (a JDK-to-only env can't configure it).
    if BT == "mvn":
        # Maven has no toolchain-resolution-at-configure issue; UpgradeJavaVersion sets source/target/release.
        return ("from", f"printf '{SETTARGET_YML}' > rewrite-bjv.yml && " +
                mvn_recipe("com.bjv.SetTarget", MIG, "-Drewrite.configLocation=rewrite-bjv.yml"))
    # Gradle: deterministic edit (proven) — rewriteRun can't even configure under an absent toolchain.
    sed = (f"sed -i -E "
           f"-e 's/JavaLanguageVersion\\.of\\([0-9]+\\)/JavaLanguageVersion.of({TO})/g' "
           f"-e 's/JavaVersion\\.VERSION_[0-9_]+/JavaVersion.VERSION_{TO}/g' "
           f"-e 's/jvmToolchain\\([0-9]+\\)/jvmToolchain({TO})/g' "
           f"-e 's/(sourceCompatibility|targetCompatibility)([ =]+)[\"'\\''0-9.]+/\\1\\2\"{TO}\"/g' "
           f"-e 's/(options\\.release[ .a-z]*set\\()[0-9]+/\\1{TO}/g' ")
    return ("from", f"for f in build.gradle build.gradle.kts; do [ -f \"$f\" ] && {sed} \"$f\"; done; "
            "grep -rnE 'of\\(|VERSION_|jvmToolchain|Compatibility' build.gradle* 2>/dev/null | head")

def bump_wrapper():
    if BT == "mvn":
        return ("from", "echo 'no wrapper bump for maven (system mvn)'")
    return ("from", f"./gradlew wrapper --gradle-version {PINNED.get(TO,'8.10.2')}")

out = []
for op in prog:
    if op["op"] == "recipe":
        cmd = (mvn_recipe(op["fqn"], artifact(op["fqn"])) if BT == "mvn"
               else gradle_recipe(op["fqn"], artifact(op["fqn"])))
        out.append((op.get("env", "to"), cmd))
    elif op["op"] == "intent" and op["name"] == "set_target":
        out.append(set_target())
    elif op["op"] == "intent" and op["name"] == "bump_wrapper":
        out.append(bump_wrapper())
    else:
        sys.stderr.write(f"unknown op {op}\n"); sys.exit(3)
for env, cmd in out:
    print(env + "\t" + cmd)
