"""Verify a hand-authored recipe by:
  1. Clone sha_from
  2. Apply the recipe via OpenRewrite (under jv_to JDK)
  3. mvn compile under jv_to (recipe must produce code that builds)
  4. Optionally: mvn test under jv_to (stricter — same as runtime)

Success criterion: build (and optionally test) passes after recipe applies.
This is the user-facing reward: does the recipe produce a working migration?

Usage: verify_recipe_builds.py <repo> <sha_from> <jv_to> <recipe_yaml> [--with-tests]
"""
import json, os, sys, subprocess, tempfile, shutil, uuid, argparse

BASE = "/home/vmihaylov/java_8_11_17_to_java_21"
ATTEMPT = f"{BASE}/attempt_6"
IMAGE = "j21-fitness:latest"
ENTRY = "/tmp/run_one_stage_v3.sh"   # has -Dmaven.test.skip=true for recipe phase
MAVEN_SETTINGS = "/home/vmihaylov/maven-config/settings.xml"
M2_CACHE = "/home/vmihaylov/.m2-fitness"
NET = "mvn-cache"
WORK = "/tmp/ff_verifybld"
os.makedirs(WORK, exist_ok=True)


def shallow_fetch(repo, sha, dst):
    url = f"https://github.com/{repo}.git"
    subprocess.run(["git", "init", "-q"], cwd=dst, capture_output=True, timeout=30)
    subprocess.run(["git", "remote", "add", "origin", url], cwd=dst, capture_output=True, timeout=10)
    r = subprocess.run(["git", "fetch", "--depth=1", "origin", sha], cwd=dst, capture_output=True, timeout=600)
    if r.returncode != 0:
        r = subprocess.run(["git", "fetch", "origin", sha], cwd=dst, capture_output=True, timeout=900)
        if r.returncode != 0: return False
    r = subprocess.run(["git", "checkout", "-q", sha], cwd=dst, capture_output=True, timeout=60)
    return r.returncode == 0


def docker_phase(work_dir, recipes_dir, log_dir, phase, jdk, recipe_file=None, timeout=600,
                 entry=None):
    log_name = f"{phase}.log"
    env = {"STAGE_JDK": str(jdk), "BUILD_TOOL": "maven",
           "STAGE_LOG": f"/out/{log_name}", "PHASE": phase}
    if recipe_file: env["STAGE_RECIPE"] = f"/recipes/{os.path.basename(recipe_file)}"
    env_args = []
    for k, v in env.items(): env_args += ["-e", f"{k}={v}"]
    cname = f"vbl_{phase}_{uuid.uuid4().hex[:10]}"
    entry_path = entry or ENTRY
    cmd = ["docker", "run", "--rm", "--name", cname,
           "-v", f"{work_dir}:/work/src",
           "-v", f"{recipes_dir}:/recipes:ro",
           "-v", f"{log_dir}:/out",
           "--network", NET,
           "-v", f"{M2_CACHE}:/root/.m2",
           "-v", f"{MAVEN_SETTINGS}:/root/.m2/settings.xml:ro",
           "-v", f"{entry_path}:/entry.sh:ro",
           *env_args,
           "--entrypoint", "bash", IMAGE, "/entry.sh"]
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        rc = r.returncode
    except subprocess.TimeoutExpired:
        for c in (["docker", "kill", cname], ["docker", "rm", "-f", cname]):
            try: subprocess.run(c, capture_output=True, timeout=15)
            except Exception: pass
        rc = 124
    log_path = os.path.join(log_dir, log_name)
    log = open(log_path).read() if os.path.exists(log_path) else ""
    return rc, log


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("repo"); ap.add_argument("sha_from"); ap.add_argument("jv_to")
    ap.add_argument("recipe_yaml")
    ap.add_argument("--with-tests", action="store_true")
    ap.add_argument("--out-verdict", help="write verdict JSON here")
    args = ap.parse_args()
    jv_to = int(args.jv_to)

    base = tempfile.mkdtemp(prefix="rbase_", dir=WORK)
    recipes = tempfile.mkdtemp(prefix="rrec_", dir=WORK)
    logs = tempfile.mkdtemp(prefix="rlog_", dir=WORK)
    verdict = {"repo": args.repo, "sha_from": args.sha_from, "jv_to": jv_to,
               "recipe": args.recipe_yaml}
    try:
        print(f"  ⤓ cloning sha_from {args.sha_from[:8]}", file=sys.stderr)
        if not shallow_fetch(args.repo, args.sha_from, base):
            verdict["error"] = "checkout_failed"; print(json.dumps(verdict, indent=2)); sys.exit(1)

        shutil.copy(args.recipe_yaml, os.path.join(recipes, "step.yml"))
        print(f"  ▶ applying recipe under JDK {jv_to}", file=sys.stderr)
        rc, log = docker_phase(base, recipes, logs, "recipe", jv_to,
                               recipe_file=os.path.join(recipes, "step.yml"), timeout=1200)
        verdict["recipe_applied"] = (rc == 0)
        verdict["recipe_rc"] = rc
        if rc != 0:
            verdict["recipe_log_tail"] = log[-1000:]

        # build under jv_to — use build_post which sets -Dmaven.compiler.release/-Djava.version
        print(f"  ▶ mvn compile under JDK {jv_to}", file=sys.stderr)
        rc, log = docker_phase(base, recipes, logs, "build_post", jv_to,
                               entry=f"{ATTEMPT}/tools/run_one_stage_v2.sh", timeout=600)
        verdict["build_ok"] = (rc == 0)
        verdict["build_rc"] = rc
        if rc != 0:
            verdict["build_log_tail"] = log[-1500:]

        if args.with_tests:
            print(f"  ▶ mvn test under JDK {jv_to}", file=sys.stderr)
            rc, log = docker_phase(base, recipes, logs, "test_post", jv_to,
                                   entry=f"{ATTEMPT}/tools/run_one_stage_v2.sh", timeout=300)
            verdict["tests_ok"] = (rc == 0)
            verdict["tests_rc"] = rc
            if rc != 0:
                verdict["tests_log_tail"] = log[-1500:]

        # short verdict line
        status = "?"
        if verdict.get("recipe_applied") and verdict.get("build_ok") and (not args.with_tests or verdict.get("tests_ok")):
            status = "PASS"
        elif not verdict.get("recipe_applied"):
            status = "RECIPE_FAIL"
        elif not verdict.get("build_ok"):
            status = "BUILD_FAIL"
        elif args.with_tests and not verdict.get("tests_ok"):
            status = "TESTS_FAIL"
        verdict["status"] = status
        print(f"  → {status}", file=sys.stderr)
    finally:
        if args.out_verdict:
            open(args.out_verdict, "w").write(json.dumps(verdict, indent=2))
        else:
            print(json.dumps(verdict, indent=2))
        for d in (base, recipes, logs):
            shutil.rmtree(d, ignore_errors=True)


if __name__ == "__main__":
    main()
