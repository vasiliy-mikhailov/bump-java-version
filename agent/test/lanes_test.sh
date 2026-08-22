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

# THE ENVIRONMENT A SWEEP IS STARTED IN, which used to be three literals inside runsweep. The
# model-settings scenarios need to launch a sweep with one of them missing, which is the whole
# question they ask, so it became a variable a scenario may replace.
#
# FABRICATED, AND OBVIOUSLY SO. Nothing under test here ever reaches a real endpoint, and a harness
# that borrowed the shape of a live key would put credential material in a public repository.
ENV_KEY="env-only-not-a-real-key"
SWEEP_ENV=()

scenario() {                       # scenario <name> <cap>
  SNAME=$1; CAP=$2
  SC=$T/work/$SNAME
  rm -rf "$SC"
  mkdir -p "$SC/root/results/claims" "$SC/root/results/postponed" "$SC/root/ws" \
           "$SC/hoptools" "$SC/fake/ps"
  export BJV_FAKE=$SC/fake
  MANROWS=""; DURS=""; POSTPONE=""
  SWEEP_ENV=(OC_BASE=http://model.invalid OC_MODEL=fake-model "OC_KEY=$ENV_KEY")
  : > "$SC/fake/durations.tsv"
  : > "$SC/fake/events.log"
  : > "$SC/fake/wedged.tsv"
  : > "$SC/fake/clones.log"
}

# A LANE THAT DOES NOT READ THE ROUND MARKER, which is what the grace and the backstop row are for.
wedged() { printf '%s\n' "$1" >> "$SC/fake/wedged.tsv"; }

# What the record says about one bump, which is the fact almost every round assertion is about.
rows()  { grep -F "\"bump\":\"$1|$2|$3|$4\"," "$SC/root/results/settlements.jsonl" 2>/dev/null; }
state() { rows "$@" | tail -1 | sed -n 's/.*"state":"\([^"]*\)".*/\1/p'; }
clones() { grep -c . "$SC/fake/clones.log" 2>/dev/null || echo 0; }

# What the settings page would have written into the run root, in the format run.sh parses.
store() {                          # store <name=value>...
  : > "$SC/root/model"
  local pair
  for pair in "$@"; do printf '%s\n' "$pair" >> "$SC/root/model"; done
}

# One variable a lane was actually handed, read back off the `docker run` the shim recorded.
lane_env() {                       # lane_env <VAR> <slug>
  sed -n "s/^$1=//p" "$SC/fake/env.bjvagent_$2" 2>/dev/null | tail -1
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
  ( cd "$SC" && env -u BJV_ENV -u OC_BASE -u OC_MODEL -u OC_KEY -u PROPOSER_API_KEY \
      BJV_RUNROOT="$SC/root" BJV_HOPTOOLS="$SC/hoptools" BJV_ALLOW_NO_CACHE=1 \
      GIT_BASE=https://git.invalid \
      LANES="$CAP" BJV_FAKE="$BJV_FAKE" \
      ${SWEEP_ENV+"${SWEEP_ENV[@]}"} \
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
# A LANE SET ASIDE WHILE IT IS RUNNING LEAVES A ROW SAYING SO.
#
# Rounds gave postponement most of a round boundary without anyone touching it: the checkout is kept
# and the next lane continues onto it. What it never gained was a row, so a lane somebody paused and
# a lane that died looked identical to a reader, both sitting on whatever progress note they were on.
# The state stays bumping rather than paused, because paused is a round boundary and rounds_done
# counts those; a person pressing a button must not spend a round of the budget.
t_pauserow() {
  scenario pauserow 2
  row long aa/pauseme cafe1 8 17 30

  echo "== pauserow: a lane set aside mid-flight says so in the record =="
  FAILED=0
  ( sleep 4; echo "a person asked for it" \
      > "$SC/root/results/postponed/$(bslug aa/pauseme cafe1 8 17)" ) &
  local marker=$!
  runsweep 60
  kill "$marker" 2>/dev/null

  rows aa/pauseme cafe1 8 17 | grep -q "set aside while it was running" \
    || bad "a postponed lane left no row saying it was set aside: $(rows aa/pauseme cafe1 8 17 | tail -1)"
  rows aa/pauseme cafe1 8 17 | grep -q "a person asked for it" \
    || bad "the row did not carry the reason the marker gave"
  rows aa/pauseme cafe1 8 17 | grep -q '"state":"paused"' \
    || bad "a pause is the same event as a round ending and must wear the same word, or a paused \
bump and a running one read identically on the page"
  [ "$(rows aa/pauseme cafe1 8 17 | grep -c '\"round\":\"1\"')" -gt 0 ] \
    || bad "the row must say which round it belongs to"
  [ "${FAILED:-0}" -eq 0 ] && ok "the pause is in the record and reads as a round boundary"
  report
}

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

# ---------------------------------------------------------------- 4. what a lane is told to call
#
# THE SENTENCE ON THE SETTINGS PAGE IS THE THING UNDER TEST. It says the key, the endpoint and the
# model saved there are what the next lane is given, and for one release it was false: the page
# wrote a file no launcher, no lane and no supervisor ever opened. These assert the property from
# the only side that can see it, the `docker run` argument list a lane is actually launched with.
#
# Six claims, and the last two are the ones that would go wrong quietly. A store that cannot be read
# must fall back to the environment rather than to nothing, because `$(cat missing)` and
# `$(cat unreadable)` are the same empty string and letting either win launches a whole sweep with
# no credentials. And a saved value must arrive as DATA: the file is written by a page on the public
# internet, so a launcher that sourced it would run whatever was typed as the host user.
t_settings() {
  local saw
  FAILED=0

  # a. THE PAGE WINS AND THE ENVIRONMENT IS UNDERNEATH.
  scenario settings_page 1
  store "key=page-saved-not-a-real-key" "endpoint=http://page.invalid/v1" "model=page-model"
  row s1 aa/s1 cafe1 8 17 1
  echo "== settings: what is saved on the page is what the lane is given =="
  runsweep 60
  [ "$(lane_env OC_KEY s1)" = "page-saved-not-a-real-key" ] \
    || bad "the lane was given the key '$(lane_env OC_KEY s1)' rather than the one on the page"
  [ "$(lane_env OC_BASE s1)" = "http://page.invalid/v1" ] \
    || bad "the lane was given the endpoint '$(lane_env OC_BASE s1)' rather than the one on the page"
  [ "$(lane_env OC_MODEL s1)" = "page-model" ] \
    || bad "the lane was given the model '$(lane_env OC_MODEL s1)' rather than the one on the page"
  # And the page can tell that it happened, which is the difference between "saved" and "in force".
  saw=$(cat "$SC/root/settings_seen" 2>/dev/null)
  [ "$saw" = "$(stat -c %Y "$SC/root/model" 2>/dev/null)" ] \
    || bad "the lane recorded '$saw' as the settings it read, not the store's own mtime"
  [ "${FAILED:-0}" -eq 0 ] && ok "the page's key, endpoint and model reached the lane, and the lane said so"
  report

  # b. WITH NOTHING SAVED, THE ENVIRONMENT IS WHAT A LANE GETS.
  FAILED=0
  scenario settings_env 1
  row s1 aa/s1 cafe1 8 17 1
  echo "== settings: with nothing saved, the environment is still what a lane gets =="
  runsweep 60
  [ "$(lane_env OC_KEY s1)" = "$ENV_KEY" ] || bad "the environment's key did not reach the lane"
  [ "$(lane_env OC_BASE s1)" = "http://model.invalid" ] || bad "the environment's endpoint did not reach the lane"
  [ "$(lane_env OC_MODEL s1)" = "fake-model" ] || bad "the environment's model did not reach the lane"
  [ "${FAILED:-0}" -eq 0 ] && ok "no store is not an empty store"
  report

  # c. AN UNREADABLE STORE IS NOT AN EMPTY STORE.
  FAILED=0
  scenario settings_unreadable 1
  store "key=page-saved-not-a-real-key" "endpoint=http://page.invalid/v1"
  chmod 000 "$SC/root/model"
  row s1 aa/s1 cafe1 8 17 1
  echo "== settings: a store that cannot be read falls back rather than launching with nothing =="
  runsweep 60
  [ "$(lane_env OC_KEY s1)" = "$ENV_KEY" ] \
    || bad "an unreadable store left the lane with '$(lane_env OC_KEY s1)' instead of the environment's key"
  [ "$(lane_env OC_BASE s1)" = "http://model.invalid" ] \
    || bad "an unreadable store left the lane with '$(lane_env OC_BASE s1)' instead of the environment's endpoint"
  # And it is said out loud, because this is otherwise indistinguishable from working.
  grep -q "cannot be read" "$SC/run.log" || bad "nothing in the log said the store could not be read"
  chmod 600 "$SC/root/model" 2>/dev/null
  [ "${FAILED:-0}" -eq 0 ] && ok "the pipeline stayed where it was, and the launcher said why"
  report

  # d. BLANK IS NOT A VALUE, FIELD BY FIELD.
  FAILED=0
  scenario settings_blank 1
  store "key=" "endpoint=http://page.invalid/v1" "model=page-model"
  row s1 aa/s1 cafe1 8 17 1
  echo "== settings: a blank saved key leaves the environment's alone, the other two still win =="
  runsweep 60
  [ "$(lane_env OC_KEY s1)" = "$ENV_KEY" ] \
    || bad "a blank saved key overrode the environment's with '$(lane_env OC_KEY s1)'"
  [ "$(lane_env OC_BASE s1)" = "http://page.invalid/v1" ] \
    || bad "a blank key line stopped the endpoint on the same page from being read"
  [ "$(lane_env OC_MODEL s1)" = "page-model" ] || bad "the page's model did not reach the lane"
  [ "${FAILED:-0}" -eq 0 ] && ok "one blank line did not empty the credential or the rest of the file"
  report

  # e. NO KEY ANYWHERE MEANS NO LANE.
  FAILED=0
  scenario settings_nokey 1
  SWEEP_ENV=(OC_BASE=http://model.invalid OC_MODEL=fake-model)
  row s1 aa/s1 cafe1 8 17 1
  echo "== settings: with no key anywhere the lane is refused rather than started =="
  runsweep 60
  [ ! -e "$SC/fake/env.bjvagent_s1" ] || bad "a lane was started with no key at all"
  grep -q "not starting a lane" "$SC/run.log" || bad "the refusal was not reported"
  [ "$RC" -ne 124 ] || bad "run.sh had to be killed"
  [ "${FAILED:-0}" -eq 0 ] && ok "an unauthenticated lane is not started, and the log says which bump"
  report

  # f. A SAVED VALUE IS DATA, NOT A COMMAND.
  FAILED=0
  scenario settings_data 1
  store 'model=$(touch '"$T"'/PWNED)x' "key=page-saved-not-a-real-key"
  rm -f "$T/PWNED"
  row s1 aa/s1 cafe1 8 17 1
  echo "== settings: a saved value reaches the lane verbatim and is never executed =="
  runsweep 60
  [ ! -e "$T/PWNED" ] || bad "a value saved on the page was executed by the launcher"
  [ "$(lane_env OC_MODEL s1)" = '$(touch '"$T"'/PWNED)x' ] \
    || bad "the saved model arrived as '$(lane_env OC_MODEL s1)' rather than verbatim"
  [ "${FAILED:-0}" -eq 0 ] && ok "the store is parsed, never sourced"
  report
}

# ---------------------------------------------------------------- 5. the round boundary
#
# A LANE HAS A WALL CLOCK NOW, AND WHAT IT COSTS A BUMP IS A ROUND RATHER THAN THE WORK. These are
# the properties the feature is: a lane that runs past its budget stops, the record says the bump is
# unfinished and which round it is on, the workspace is still there, and the next lane continues on
# it rather than cloning again.
#
# THE BUDGET IS IN SECONDS HERE AND SIX HOURS IN PRODUCTION. BJV_ROUND_SECONDS is the seam; without
# it every scenario below would take a working day.

t_budget() {
  scenario budget 2
  # WEDGED ON PURPOSE. This lane never reads the marker, which is the case the grace and the
  # launcher's own boundary row exist for: a container that has stopped reading anything at all.
  # It is also the only hang guard this harness has ever had.
  row stuck aa/stuck cafe1 8 17 60
  wedged stuck
  row quick bb/quick cafe1 8 17 1

  echo "== budget: a lane that outlasts its round is stopped, and the bump is not finished =="
  FAILED=0
  SWEEP_ENV+=(BJV_ROUND_SECONDS=3 BJV_ROUND_GRACE_SECONDS=2 BJV_MAX_ROUNDS=2)
  runsweep 90

  local first
  first=$(rows aa/stuck cafe1 8 17 | grep '"state":"paused"' | head -1)
  [ -n "$first" ] || bad "the round never ended: $(rows aa/stuck cafe1 8 17 | tail -1)"
  printf '%s' "$first" | grep -q '"round":"1"' \
    || bad "the boundary row does not carry round 1: $first"
  # THE FINGERPRINT IS LIFTED, NOT RECOMPUTED. The shell cannot hash a prompt store it has no reader
  # for, and the next lane's resume decision is a comparison of exactly these four fields.
  printf '%s' "$first" | grep -q '"prompts":"54906737","boms":"bb42094f"' \
    || bad "the four pipeline fields were not carried onto the boundary row: $first"
  grep -q "did not end at a stage edge" "$SC/run.log" \
    || bad "the wedged lane was never killed, so nothing bounded it"
  grep -q "round 1 ended" "$SC/run.log" || bad "the bump was not offered another round"
  # PRESERVED ACROSS A KILL TOO, which is the half that would be easy to lose: the launcher writes
  # the boundary row for a lane that could not, and the keep rule reads that row.
  grep -q "ws stuck 2" "$SC/fake/events.log" \
    || bad "round two did not find round one's workspace: $(grep '	ws ' "$SC/fake/events.log")"
  [ "$RC" -ne 124 ] || bad "run.sh had to be killed: it did not terminate"
  [ "${FAILED:-0}" -eq 0 ] && ok "the round ended, the row says which one, and the checkout survived"
  report
}

t_continues() {
  scenario continues 2
  # Not wedged: this lane reads the marker and settles itself between stages, which is the ordinary
  # path and the one that loses nothing.
  row long aa/long cafe1 8 17 60

  echo "== continues: the next lane picks up the same workspace instead of cloning again =="
  FAILED=0
  SWEEP_ENV+=(BJV_ROUND_SECONDS=3 BJV_ROUND_GRACE_SECONDS=60 BJV_MAX_ROUNDS=9)
  runsweep 90

  # THE ASSERTION THAT DECIDES WHETHER THE FEATURE HELPS OR HURTS. A round boundary that re-cloned
  # would turn a long bump into an infinite sequence of fresh starts, which is worse than no feature
  # at all, and it would look exactly like this from the outside except for these two lines.
  [ "$(clones)" = "1" ] || bad "the workspace was cloned $(clones) times; a kept one is cloned once"
  grep -q "ws long 2" "$SC/fake/events.log" \
    || bad "the second round did not continue the first one's workspace"
  grep -q '"state":"paused"' "$SC/root/results/settlements.jsonl" || bad "no round ever ended"
  grep -q "did not end at a stage edge" "$SC/run.log" \
    && bad "a lane that stops between stages must not need killing"
  [ "$(state aa/long cafe1 8 17)" = "PASS" ] \
    || bad "the continued round did not finish: $(state aa/long cafe1 8 17)"
  [ "$RC" -ne 124 ] || bad "run.sh had to be killed: it did not terminate"
  [ "${FAILED:-0}" -eq 0 ] && ok "one clone, two rounds, one workspace, and a verdict at the end"
  report
}

t_repass() {
  scenario repass 1
  # THE SCHEDULING GAP, WHICH THE FEATURE DOES NOT WORK WITHOUT. run.sh makes one pass over the
  # manifest; a bump paused at row one while the pass is at row three is never reconsidered unless
  # something re-offers it. On a 1400-row sweep that wait is weeks, and usually for ever.
  row first aa/first cafe1 8 17 60
  row two   bb/two   cafe1 8 17 1
  row three cc/three cafe1 8 17 1

  echo "== repass: a bump paused early is offered again before the pass is over =="
  FAILED=0
  SWEEP_ENV+=(BJV_ROUND_SECONDS=3 BJV_ROUND_GRACE_SECONDS=60 BJV_MAX_ROUNDS=9)
  runsweep 90

  [ "$(grep -c "^start first" <(cut -f2- "$SC/fake/events.log"))" -ge 2 ] \
    || bad "the paused bump was launched once and never came back"
  for s in two three; do
    grep -q "^start $s" <(cut -f2- "$SC/fake/events.log") \
      || bad "$s never ran: a resume took the slot work that had never started was waiting for"
  done
  [ "$RC" -ne 124 ] || bad "run.sh had to be killed: it did not terminate"
  [ "${FAILED:-0}" -eq 0 ] && ok "the paused bump came back and the unstarted rows still ran"
  report
}

t_roundcap() {
  scenario roundcap 1
  # A bump that never finishes has to stop being offered, or the launcher cannot terminate at all.
  row forever aa/forever cafe1 8 17 60
  wedged forever

  echo "== roundcap: a bump that never finishes stops costing lanes, and says so =="
  FAILED=0
  SWEEP_ENV+=(BJV_ROUND_SECONDS=2 BJV_ROUND_GRACE_SECONDS=2 BJV_MAX_ROUNDS=2)
  runsweep 90

  [ "$(state aa/forever cafe1 8 17)" = "out-of-rounds" ] \
    || bad "after the cap the record says '$(state aa/forever cafe1 8 17)'"
  # NOT A VERDICT ABOUT THE PROJECT, and it must not read like one.
  rows aa/forever cafe1 8 17 | tail -1 | grep -q "nothing here is a judgement about the project" \
    || bad "the account does not say what the state means"
  [ ! -d "$SC/root/ws/forever" ] || bad "a bump nobody will run again is still holding a workspace"
  [ "$RC" -ne 124 ] || bad "run.sh did not terminate: the cap is what makes it able to"
  [ "${FAILED:-0}" -eq 0 ] && ok "two rounds, then a terminal state, a freed workspace and an exit"
  report
}

t_wipeonsettle() {
  scenario wipeonsettle 2
  row a aa/a cafe1 8 17 1
  row b bb/b cafe1 8 17 1

  echo "== wipeonsettle: a bump that reached a verdict does not go on holding a checkout =="
  FAILED=0
  runsweep 60
  [ ! -d "$SC/root/ws/a" ] || bad "a settled bump kept its workspace: 82% of the live tree is this"
  [ ! -d "$SC/root/ws/b" ] || bad "a settled bump kept its workspace"
  [ "${FAILED:-0}" -eq 0 ] && ok "the disk a finished bump was holding comes back"
  report

  FAILED=0
  scenario keepws 1
  SWEEP_ENV+=(BJV_KEEP_WS=1)
  row a aa/a cafe1 8 17 1
  echo "== wipeonsettle: and whoever wants to look at one can keep it =="
  runsweep 60
  [ -d "$SC/root/ws/a" ] || bad "BJV_KEEP_WS did not keep the workspace"
  [ "${FAILED:-0}" -eq 0 ] && ok "BJV_KEEP_WS keeps it"
  report
}

t_diskfloor() {
  scenario diskfloor 1
  row long aa/long cafe1 8 17 60

  echo "== diskfloor: below the floor a boundary wipes instead of keeping =="
  FAILED=0
  # WIPING IS ALWAYS SAFE AND KEEPING IS THE RISKY CHOICE, so the floor costs time and never
  # correctness: the next lane clones, the journal does not stand on the tree, and the bump starts
  # clean with a real baseline.
  SWEEP_ENV+=(BJV_ROUND_SECONDS=3 BJV_ROUND_GRACE_SECONDS=30 BJV_MAX_ROUNDS=9
              BJV_FREE_GB=5 BJV_WS_FLOOR_GB=60)
  runsweep 90

  grep -q "below the 60GB floor" "$SC/run.log" \
    || bad "the floor was never reached, so this scenario proved nothing: $(grep -c . "$SC/run.log") log lines"
  [ "$(clones)" -ge 2 ] || bad "the workspace was kept anyway: cloned $(clones) times"
  [ "$RC" -ne 124 ] || bad "run.sh had to be killed"
  [ "${FAILED:-0}" -eq 0 ] && ok "a full disk costs a re-clone rather than a wrong measurement"
  report
}

t_imageid() {
  scenario imageid 1
  row first  aa/first  cafe1 8 17 3
  row second bb/second cafe1 8 17 2

  echo "== imageid: a lane carries the image the tag meant when THAT lane started =="
  FAILED=0
  echo "sha256:aaaa" > "$SC/fake/image_id"
  # THE DEPLOY, MID-SWEEP, which is the ordinary case rather than the awkward one: a launcher runs
  # for days and the tag moves under it several times, and nothing tells it. Whatever it resolved
  # once is what every later lane gets called, so the record names an image that did not run.
  ( sleep 2; echo "sha256:bbbb" > "$SC/fake/image_id" ) &
  local deploy=$!
  runsweep 60
  kill "$deploy" 2>/dev/null

  [ "$(lane_env BJV_IMAGE_ID first)" = "sha256:aaaa" ] \
    || bad "the first lane was stamped '$(lane_env BJV_IMAGE_ID first)' rather than what the tag \
meant when it started"
  [ "$(lane_env BJV_IMAGE_ID second)" = "sha256:bbbb" ] \
    || bad "a lane started after the tag moved was stamped '$(lane_env BJV_IMAGE_ID second)', the \
image from before it; a settlement row then names a pipeline that never ran, and a resume compares \
against it"
  [ "${FAILED:-0}" -eq 0 ] && ok "each lane carries the image the tag resolved to when it started"
  report
}

case "${1:-all}" in
  idle) t_idle ;;
  inpass) t_inpass ;;
  terminate) t_terminate ;;
  settings) t_settings ;;
  budget) t_budget ;;
  continues) t_continues ;;
  repass) t_repass ;;
  roundcap) t_roundcap ;;
  wipeonsettle) t_wipeonsettle ;;
  diskfloor) t_diskfloor ;;
  pauserow) t_pauserow ;;
  imageid) t_imageid ;;
  all) t_idle; t_inpass; t_terminate; t_settings; t_budget; t_continues; t_repass; \
       t_roundcap; t_wipeonsettle; t_diskfloor; t_pauserow; t_imageid ;;
  *) echo "unknown scenario: $1"; exit 2 ;;
esac

echo "$pass passed, $fail failed"
[ "$fail" -eq 0 ]
