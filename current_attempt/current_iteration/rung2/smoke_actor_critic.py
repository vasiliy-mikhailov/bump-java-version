#!/usr/bin/env python3
"""Deterministic smoke test for the actor-critic CRITIC (critic.py).

Builds synthetic ACTIONS (a compiled workspace + the agent's program/log/diff) spanning the whole reward
spectrum and asserts the critic returns the intended value. This verifies the composed critic end to end:
the gate (build/conserve/target), the parametric-recipe penalty, the manual-edit penalty, the free
hop-fixed intents (target + wrapper credited at 0), and cheat detection. Stdlib only; runs in python:3-slim.
Exit 0 iff every case matches.
"""
import os, sys, struct, tempfile, json
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
from critic import critic

def klass(ws, rel, major):
    p = os.path.join(ws, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "wb").write(b"\xca\xfe\xba\xbe\x00\x00" + struct.pack(">H", major))

def surefire(ws, cls, passing):
    d = os.path.join(ws, "target", "surefire-reports"); os.makedirs(d, exist_ok=True)
    tcs = "".join(f'<testcase classname="{cls}" name="{n}"/>' for n in passing)
    open(os.path.join(d, f"TEST-{cls}.xml"), "w").write(f"<testsuite>{tcs}</testsuite>")

def agentlog(O, edits):                      # edits = [(command, path)]
    open(os.path.join(O, "agent.log"), "w").write(
        "".join(f'command: "{c}"\npath: "{p}"\n' for c, p in edits))

def diff(O, files):                          # files = {path: [changed_line_with_+/-_prefix, ...]}
    out = []
    for f, lines in files.items():
        out.append(f"diff --git a/{f} b/{f}"); out.extend(lines)
    open(os.path.join(O, "agent.diff"), "w").write("\n".join(out) + "\n")

def rewrite(O, body):
    open(os.path.join(O, "rewrite.yml"), "w").write(
        "type: specs.openrewrite.org/v1beta/recipe\nname: com.bjv.Bump\nrecipeList:\n" + body)

def preset(O, names): open(os.path.join(O, "pre_set.txt"), "w").write("\n".join(names))

UJV = "  - org.openrewrite.java.migrate.UpgradeJavaVersion:\n      version: 21\n"   # free at TO=21
V65 = 65   # JDK 21 class-file major

def b_clean(O, ws):
    klass(ws, "target/classes/A.class", V65); surefire(ws, "com.A", ["t1", "t2"]); preset(O, ["com.A#t1", "com.A#t2"])
    rewrite(O, UJV); agentlog(O, []); diff(O, {}); return 0, 0

def b_parametric(O, ws):
    klass(ws, "target/classes/A.class", V65); surefire(ws, "com.A", ["t1"]); preset(O, ["com.A#t1"])
    rewrite(O, UJV + "  - org.openrewrite.java.ChangeMethodName:\n      methodPattern: 'a.b foo()'\n      newMethodName: bar\n")
    agentlog(O, []); diff(O, {}); return 0, 0

def b_freehand(O, ws):                        # free target edit + free wrapper edit, both hand-applied
    klass(ws, "build/classes/java/main/A.class", V65); surefire(ws, "com.A", ["t1"]); preset(O, ["com.A#t1"]); rewrite(O, UJV)
    agentlog(O, [("str_replace", "/work/build.gradle"), ("str_replace", "/work/gradle/wrapper/gradle-wrapper.properties")])
    diff(O, {"build.gradle": ["-sourceCompatibility = JavaVersion.VERSION_17", "+sourceCompatibility = JavaVersion.VERSION_21"],
             "gradle/wrapper/gradle-wrapper.properties": ["-distributionUrl=x/gradle-7.6-bin.zip", "+distributionUrl=x/gradle-8.10.2-bin.zip"]})
    return 0, 0

def b_oneedit(O, ws):                         # one non-target hand edit -> 0.9
    klass(ws, "target/classes/A.class", V65); surefire(ws, "com.A", ["t1"]); preset(O, ["com.A#t1"]); rewrite(O, UJV)
    agentlog(O, [("str_replace", "/work/src/main/java/Foo.java")])
    diff(O, {"src/main/java/Foo.java": ["-int x = 1;", "+int x = 2;"]}); return 0, 0

def b_buildfail(O, ws):
    klass(ws, "target/classes/A.class", V65); surefire(ws, "com.A", ["t1"]); preset(O, ["com.A#t1"]); rewrite(O, UJV)
    agentlog(O, []); diff(O, {}); return 1, 0            # comprc != 0 -> FAIL_build_post

def b_lost(O, ws):
    klass(ws, "target/classes/A.class", V65); surefire(ws, "com.A", ["t1"]); preset(O, ["com.A#t1", "com.A#t2"])
    rewrite(O, UJV); agentlog(O, []); diff(O, {}); return 0, 0   # t2 missing post -> FAIL_test_conservation

def b_nomain(O, ws):
    klass(ws, "target/test-classes/ATest.class", V65); surefire(ws, "com.A", ["t1"]); preset(O, ["com.A#t1"])
    rewrite(O, UJV); agentlog(O, []); diff(O, {}); return 0, 0   # only test bytecode -> FAIL_no_main_bytecode

def b_cheat(O, ws):                           # gate would PASS, but the agent touched a test file
    klass(ws, "target/classes/A.class", V65); surefire(ws, "com.A", ["t1"]); preset(O, ["com.A#t1"]); rewrite(O, UJV)
    agentlog(O, [("str_replace", "/work/src/test/java/FooTest.java")])
    diff(O, {"src/test/java/FooTest.java": ["+assertTrue(true);"]}); return 0, 0

CASES = [
    ("clean_free_bump",         b_clean,      1.0),
    ("one_parametric_recipe",   b_parametric, 0.9),
    ("free_hand_wrapper+target", b_freehand,  1.0),
    ("one_manual_edit",         b_oneedit,    0.9),
    ("build_fail",              b_buildfail,  0.0),
    ("test_lost",               b_lost,       0.0),
    ("no_main_bytecode",        b_nomain,     0.0),
    ("cheat_touched_test",      b_cheat,      0.0),
]

def run_case(name, build, expect):
    with tempfile.TemporaryDirectory() as tmp:
        O, ws = os.path.join(tmp, "O"), os.path.join(tmp, "ws"); os.makedirs(O); os.makedirs(ws)
        open(os.path.join(O, "cwe.json"), "w").write("{}")
        comprc, testrc = build(O, ws)
        r = critic(O, ws, os.path.join(O, "pre_set.txt"), 17, 21, comprc, testrc,
                   os.path.join(O, "cwe.json"), "smoke/" + name)
        ok = abs(r["reward"] - expect) < 1e-9
        print(f"{'PASS' if ok else 'FAIL'}  {name:24} reward={r['reward']:<4} (want {expect})  "
              f"verdict={r['verdict']:<22} param={r['parametric']} edits={r['edits']} cheated={r['cheated']}")
        return ok

def main():
    results = [run_case(n, b, e) for n, b, e in CASES]
    n_ok = sum(results)
    print(f"\n{n_ok}/{len(CASES)} cases matched the intended reward" + ("" if n_ok == len(CASES) else "  <-- MISMATCH"))
    sys.exit(0 if n_ok == len(CASES) else 1)

if __name__ == "__main__":
    main()
