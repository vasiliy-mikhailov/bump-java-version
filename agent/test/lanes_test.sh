#!/usr/bin/env bash
# LANE-OCCUPANCY TESTS FOR run.sh. Seconds, no containers, no model, no clones.
#
# What is faked is only the outside: `docker` and `git` are PATH shims (see bin/docker), so one(),
# inflight(), running(), the watchdog and the round loop are the production code and the claim
# files under $RUNROOT/results/claims are real files. A lane is a shim process that holds a
# registry entry for a controllable number of seconds and settles its bump on the way out.
#
# What is asserted is OBSERVED CONCURRENCY OVER TIME, sampled four times a second, never log text:
# log lines change with every edit, the property does not.
#
#   bash lanes_test.sh            # all scenarios
#   bash lanes_test.sh idle       # one of: idle, inpass, terminate
set -uo pipefail

T=${T:-/t}
RUN=${RUN:-$T/run.sh}
[ -f "$RUN" ] || { echo "no script under test at $RUN"; exit 2; }
chmod +x "$T/bin/docker" "$T/bin/git" 2>/dev/null
export PATH=$T/bin:$PATH

for p in awk sed grep find timeout date; do
  command -v "$p" >/dev/null || { echo "harness needs $p"; exit 2; }
done

pass=0; fail=0
TAB=$(printf '\t')

now_ms() { date +%s%3N; }

# ---------------------------------------------------------------- scenario scaffolding

MANROWS=""; DURS=""; POSTPONE=""
scenario() {                       # scenario <name> <cap>
  SNAME=$1; CAP=$2
  SC=$T/work/$SNAME
  rm -rf "$SC"
  mkdir -p "$SC/root/results/claims" "$SC/root/results/postponed" "$SC/root/ws" \
           "$SC/hoptools" "$SC/fake/ps"
  export BJV_FAKE=$SC/fake
  MANROWS=""; DURS=""; POSTPONE=""
  : > "$SC/fake/durations.tsv"
  : > "$SC/fake/events.log"
}

row() {                            # row <slug> <repo> <sha> <from> <to> <seconds>
  MANROWS="$MANROWS$1$TAB$2$TAB$3$TAB$4$TAB$5"$'\n'
  printf '%s\t%s\n' "$1" "$6" >> "$SC/fake/durations.tsv"
}

bslug() { printf '%s' "$1|$2|$3|$4" | sed 's/[^A-Za-z0-9]\+/_/g'; }

postpone() {                       # postpone <repo> <sha> <from> <to>   (the supervisor's marker)
  echo "set aside for the test" > "$SC/root/results/postponed/$(bslug "$1" "$2" "$3" "$4")"
}

settle() {                         # settle <repo> <sha> <from> <to>     (a terminal verdict)
  printf '{"bump":"%s|%s|%s|%s","state":"PASS"}\n' "$1" "$2" "$3" "$4" \
    >> "$SC/root/results/settlements.jsonl"
}

# Four samples a second of "how many lanes are live", which is the count running() is trying to
# hold at the cap. Written as elapsed-ms, count, names.
sampler() {
  local out=$1 t0 nm n
  t0=$(now_ms)
  while :; do
    nm=$(ls "$BJV_FAKE/ps" 2>/dev/null | tr '\n' ',')
    n=$(printf '%s' "$nm" | tr -cd ',' | wc -c)
    printf '%s\t%s\t%s\n' "$(( $(now_ms) - t0 ))" "$n" "$nm" >> "$out"
    sleep 0.25
  done
}

runsweep() {                       # runsweep <timeout-seconds>
  printf '%s' "$MANROWS" > "$SC/manifest.tsv"
  : > "$SC/samples.tsv"
  sampler "$SC/samples.tsv" & SAMPLER=$!
  local t0 rc
  t0=$(now_ms)
  ( cd "$SC" && env -u BJV_ENV \
      BJV_RUNROOT="$SC/root" BJV_HOPTOOLS="$SC/hoptools" BJV_ALLOW_NO_CACHE=1 \
      GIT_BASE=https://git.invalid OC_BASE=http://model.invalid OC_MODEL=fake OC_KEY=fake \
      LANES="$CAP" BJV_FAKE="$BJV_FAKE" \
      timeout "$1" bash "$RUN" "$SC/manifest.tsv" ) > "$SC/run.log" 2>&1
  rc=$?
  ELAPSED_MS=$(( $(now_ms) - t0 ))
  RC=$rc
  kill "$SAMPLER" 2>/dev/null; wait "$SAMPLER" 2>/dev/null
  [ "$rc" -eq 124 ] && echo "  run.sh did NOT terminate within $1s"
  return 0
}

# THE MEASUREMENT, BY NAME AND NOT BY COUNT. worst_gap_ms is the longest wait between a lane
# ending and the next lane starting, counted only while rows that have never been launched still
# exist: that is "a freed slot gets refilled", which is the whole property in both directions.
#
# Counting samples below the cap instead looks equivalent and is not. Lanes hand over within
# milliseconds of each other, so an instant of full occupancy falls between two samples and two
# ordinary handovers read as one long stall. Names do not have that problem: a name that was never
# live before is a slot that was refilled, whatever the sampler happened to catch.
#
# peak_in_window is the same property said the way the bug report says it: with the straggler still
# running, did occupancy come back to the cap at all.
measure() {                        # measure <total-rows> [straggler-container] [window-from-ms]
  awk -F'\t' -v cap="$CAP" -v total="$1" -v str="${2:-}" -v from="${3:-0}" '
    {
      t=$1+0; c=$2+0; nm=$3
      n=split(nm, a, ",")
      split("", cur, ",")
      newn=0
      for (k=1; k<=n; k++) if (a[k] != "") {
        cur[a[k]]=1
        if (!(a[k] in seen)) { seen[a[k]]=1; nseen++; newn++ }
      }
      gone=0
      for (x in prev) if (!(x in cur)) gone++
      split("", prev, ",")
      for (x in cur) prev[x]=1
      last=t
      if (newn > 0 && pending) { g=t-dropt; if (g>worst) { worst=g; worstat=dropt }; pending=0 }
      if (gone > 0 && newn == 0 && nseen < total && !pending) { pending=1; dropt=t }
      if (str != "" && t >= from && index(nm, str ",") > 0 && c > peak) peak=c
    }
    END {
      if (pending && nseen < total) { g=last-dropt; if (g>worst) { worst=g; worstat=dropt } }
      printf "%d %d %d %d\n", worst, worstat, peak, nseen
    }' "$SC/samples.tsv"
}

timeline() {                       # one digit per sample, four per second
  awk -F'\t' '{ printf "%s", ($2>9 ? "+" : $2) } END { print "" }' "$SC/samples.tsv"
}

ok()  { echo "  ok   $*"; }
bad() { echo "  FAIL $*"; fail=$((fail+1)); FAILED=1; }

report() {
  if [ "${FAILED:-0}" -eq 0 ]; then pass=$((pass+1)); echo "PASS $SNAME"; else echo "FAIL $SNAME"; fi
  echo "  lanes over time (one digit per 0.25s, cap $CAP):"
  echo "    $(timeline)"
  echo
}

# ---------------------------------------------------------------- 1. the straggler
#
# More rows than the cap and one lane that outlives the rest. While it runs, and while unsettled
# work is still on the manifest, the other cap-1 slots must be refilled. The unsettled work here is
# the postponed set, which is what the live sweep has left: postponement frees a slot for work that
# can progress, so once it is all that remains it IS the work.
t_idle() {
  scenario idle 4
  row straggler aa/straggler cafe1 8 17 20
  row short1    bb/short1    cafe1 8 17 2
  row short2    bb/short2    cafe1 8 17 2
  row short3    bb/short3    cafe1 8 17 2
  # The set-aside lanes outlast the window the peak is measured in on purpose: a correct loop
  # refills the freed slots within a couple of seconds, so a window that opened later than that
  # would measure the refilled lanes AFTER they had already finished and read as a stall.
  row post1     cc/post1     cafe1 8 17 10
  row post2     cc/post2     cafe1 8 17 10
  row post3     cc/post3     cafe1 8 17 10
  postpone cc/post1 cafe1 8 17
  postpone cc/post2 cafe1 8 17
  postpone cc/post3 cafe1 8 17

  echo "== idle: seven rows, cap 4, one 20s lane, three set aside =="
  FAILED=0
  runsweep 90
  read -r worst worstat peak launched <<EOF
$(measure 7 bjvagent_straggler 5000)
EOF
  echo "  worst wait for a freed slot ${worst}ms (from ${worstat}ms), peak while the straggler ran: $peak, launched $launched/7"
  [ "$worst" -le 5000 ] || bad "a freed slot went unused for ${worst}ms while unlaunched rows remained (allowed 5000ms)"
  [ "$peak" -ge "$CAP" ] || bad "concurrency never returned to the cap ($peak of $CAP) while the straggler ran"
  [ "$launched" -eq 7 ] || bad "only $launched of 7 rows ever started"
  [ "$RC" -ne 124 ] || bad "run.sh had to be killed: it did not terminate"
  [ "${FAILED:-0}" -eq 0 ] && ok "the cap was held while the straggler ran, and the sweep still ended"
  report
}

# ---------------------------------------------------------------- 2. mid-pass replacement
#
# The property the inner poll already provides, pinned down so that fixing the one above cannot
# quietly cost it: mid-manifest a finished lane is replaced within seconds.
t_inpass() {
  scenario inpass 2
  # Uneven lengths on purpose: lanes that all end together turn the whole cap over at once, which
  # is not the case that matters. Here one lane ends while its neighbour is still working, so the
  # freed slot has to be refilled while the pass is still reading the manifest.
  local i d
  for i in 1 2 3 4 5 6; do
    d=2; [ $(( i % 2 )) -eq 0 ] && d=4
    row "r$i" "aa/r$i" cafe1 8 17 "$d"
  done

  echo "== inpass: six rows, cap 2, alternating 2s and 4s lanes =="
  FAILED=0
  runsweep 90
  read -r worst worstat peak launched <<EOF
$(measure 6)
EOF
  echo "  worst wait for a freed slot ${worst}ms (from ${worstat}ms), launched $launched/6, wall ${ELAPSED_MS}ms"
  [ "$worst" -le 5000 ] || bad "a freed slot went unused for ${worst}ms mid-manifest (allowed 5000ms)"
  [ "$launched" -eq 6 ] || bad "only $launched of 6 rows ever started"
  [ "$RC" -ne 124 ] || bad "run.sh had to be killed: it did not terminate"
  [ "${FAILED:-0}" -eq 0 ] && ok "a finished lane was replaced within seconds, every row ran"
  report
}

# ---------------------------------------------------------------- 3. termination
#
# A sweep whose work is genuinely finished has to stop. Settled rows are skipped, the set-aside ones
# are all that is left, so they are cleared once, run, and the loop ends.
t_terminate() {
  scenario terminate 2
  row done1 aa/done1 cafe1 8 17 1
  row done2 aa/done2 cafe1 8 17 1
  row done3 aa/done3 cafe1 8 17 1
  row late1 bb/late1 cafe1 8 17 1
  row late2 bb/late2 cafe1 8 17 1
  settle aa/done1 cafe1 8 17
  settle aa/done2 cafe1 8 17
  settle aa/done3 cafe1 8 17
  postpone bb/late1 cafe1 8 17
  postpone bb/late2 cafe1 8 17

  echo "== terminate: three settled, two set aside, nothing else =="
  FAILED=0
  runsweep 60
  read -r worst worstat peak launched <<EOF
$(measure 2)
EOF
  echo "  exit $RC after ${ELAPSED_MS}ms, launched $launched/2"
  [ "$RC" -ne 124 ] || bad "run.sh did not terminate"
  [ "$ELAPSED_MS" -lt 30000 ] || bad "took ${ELAPSED_MS}ms to finish work that lasts one second"
  [ "$launched" -eq 2 ] || bad "the set-aside rows never ran ($launched of 2)"
  [ "${FAILED:-0}" -eq 0 ] && ok "the set-aside work ran and the sweep ended"
  report
}

case "${1:-all}" in
  idle) t_idle ;;
  inpass) t_inpass ;;
  terminate) t_terminate ;;
  all) t_idle; t_inpass; t_terminate ;;
  *) echo "unknown scenario: $1"; exit 2 ;;
esac

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
