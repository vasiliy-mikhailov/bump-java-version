#!/bin/bash
# compare_runs.sh <old_corpus> [new_corpus] -- per-slug verdict diff between two SAME-ORDER runs (e.g. an
# archived genome vs the current one). roundrobin's slug->candidate map is deterministic per hop (rr_H_N is
# always line N+1 of cand_H.txt), so as long as the /tmp/q queues are unchanged, rr_H_N is the SAME repo in
# both runs and the comparison is valid datapoint-by-datapoint. Reports FAIL->PASS (fixed), PASS->FAIL
# (regressed), and the still-FAIL set. Example: compare_runs.sh /tmp/hoptest.mixedgenome_20260701_1044
set -uo pipefail
OLD="$1"; NEW="${2:-/tmp/hoptest}"
[ -d "$OLD" ] || { echo "no old corpus: $OLD" >&2; exit 1; }
vp(){ grep -aoE "VERDICT [A-Za-z_0-9]+" "$1/verdict.txt" 2>/dev/null | head -1 | awk '{print $2}'; }
fixed=0; reg=0; sfail=0; spass=0; both=0; rows=""
for d in "$NEW"/rr_*_*/; do
  s=$(basename "$d")
  [ -f "$OLD/$s/verdict.txt" ] && [ -f "$NEW/$s/verdict.txt" ] || continue
  ov=$(vp "$OLD/$s"); nv=$(vp "$NEW/$s"); { [ -z "$ov" ] || [ -z "$nv" ]; } && continue
  both=$((both+1))
  op=PASS; echo "$ov" | grep -q PASS || op=FAIL
  np=PASS; echo "$nv" | grep -q PASS || np=FAIL
  if   [ "$op" = FAIL ] && [ "$np" = PASS ]; then fixed=$((fixed+1)); rows="$rows$s\t$ov\t$nv\tFIXED\n"
  elif [ "$op" = PASS ] && [ "$np" = FAIL ]; then reg=$((reg+1));    rows="$rows$s\t$ov\t$nv\tREGRESSED\n"
  elif [ "$op" = FAIL ];                       then sfail=$((sfail+1)); rows="$rows$s\t$ov\t$nv\tstill-fail\n"
  else spass=$((spass+1)); fi
done
echo "compared $both slugs present in BOTH runs:"
echo "  FIXED (FAIL->PASS):   $fixed"
echo "  REGRESSED (PASS->FAIL): $reg   <-- investigate any of these"
echo "  still-fail:           $sfail"
echo "  still-pass:           $spass"
echo
[ -n "$rows" ] && printf "$rows" | sort -t$'\t' -k4 | column -t -s$'\t'
