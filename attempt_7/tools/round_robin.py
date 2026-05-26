"""Multi-pass round-robin scheduler for item 1 (recipe iterator).

For each pass:
  - call iterate_repo.iterate_one(stage, max_attempts = pass_no * K) on every still-FAILing stage
  - iterate_one resumes from cached trajectory automatically
  - count how many new PASSes this pass produced
Stop when a pass produces zero new PASSes.

Usage:
  round_robin.py --sample stages.json [--K 5] [--workers 6] [--max-passes 100]
"""
import os, sys, json, time, argparse, threading
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, "/home/vmihaylov/java_8_11_17_to_java_21/attempt_7/tools")
from iterate_repo import iterate_one, OUT_DIR


def load_verdict(stage):
    slug = f"{stage['repo'].replace('/', '_')}__J{stage['jv_from']}toJ{stage['jv_to']}"
    p = f"{OUT_DIR}/{slug}/trajectory.json"
    if not os.path.exists(p): return None, 0
    t = json.load(open(p))
    return t.get("final_verdict"), len(t.get("history", []))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", required=True, help="JSON list of stages")
    ap.add_argument("--K", type=int, default=5, help="attempts per slice per pass")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--max-passes", type=int, default=100)
    ap.add_argument("--log", default="/tmp/round_robin.log")
    args = ap.parse_args()

    stages = json.load(open(args.sample))
    for s in stages: s.setdefault("jv_to", 21)
    n = len(stages)
    print(f"== round-robin: {n} stages, K={args.K}, workers={args.workers}, max_passes={args.max_passes} ==", flush=True)

    cumulative_curve = []  # [(pass_no, total_pass, new_this_pass, wall_s)]
    for pass_no in range(1, args.max_passes + 1):
        budget = pass_no * args.K
        # Count current PASSes BEFORE this pass
        passed_before = sum(1 for s in stages if load_verdict(s)[0] == "PASS")
        # Run iterate_one on every stage (resumes / skips internally)
        pass_t0 = time.time()
        lock = threading.Lock()
        done = [0]
        def go(s):
            try:
                iterate_one(s, max_attempts=budget)
            except Exception as e:
                print(f"[{s['repo']}] EXC: {type(e).__name__}: {e}", flush=True)
            with lock:
                done[0] += 1
                if done[0] % 25 == 0:
                    print(f"   pass {pass_no} progress: {done[0]}/{n}", flush=True)
        if args.workers == 1:
            for s in stages: go(s)
        else:
            with ThreadPoolExecutor(max_workers=args.workers) as ex:
                list(ex.map(go, stages))
        pass_wall_s = round(time.time() - pass_t0, 1)
        passed_after = sum(1 for s in stages if load_verdict(s)[0] == "PASS")
        new_this_pass = passed_after - passed_before
        cumulative_curve.append({"pass": pass_no, "budget_per_repo": budget,
                                  "passed_cumulative": passed_after,
                                  "new_this_pass": new_this_pass,
                                  "pass_wall_s": pass_wall_s})
        with open(args.log, "w") as f:
            json.dump(cumulative_curve, f, indent=2)
        print(f"== pass {pass_no} done: budget={budget} new={new_this_pass} cum_pass={passed_after}/{n} ({100*passed_after/n:.1f}%) wall_s={pass_wall_s} ==", flush=True)
        if new_this_pass == 0:
            print(f"== convergence: pass {pass_no} produced 0 new PASSes; stopping ==", flush=True)
            break

    # Final report
    print()
    print("=== round-robin complete ===")
    for c in cumulative_curve:
        print(f"  pass {c['pass']:>2d} (budget={c['budget_per_repo']:>3d}): "
              f"+{c['new_this_pass']:>3d}  cum={c['passed_cumulative']:>4d}/{n}  "
              f"({100*c['passed_cumulative']/n:>5.1f}%)  wall_s={c['pass_wall_s']}")


if __name__ == "__main__":
    main()
