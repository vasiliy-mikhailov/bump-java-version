#!/bin/sh
# RE-TAKE THE FIXTURES THE CONFORMANCE TEST RUNS AGAINST.
#
# WHY THE RESPONSES ARE COMMITTED RATHER THAN FETCHED. A test that talks to the running dashboard
# only runs where the dashboard is running, which is one machine. Everywhere else it is skipped, and
# a skipped test is a green tick that asserts nothing. The same is true of a test gated on an
# environment variable: it runs on the day somebody sets it and never again. So the bytes the server
# actually sent are checked in, and the test runs unconditionally, on every `pnpm test`, on a laptop
# with no docker and no network.
#
# The cost is that a fixture is a photograph and the server keeps moving. That is what this script
# is for: re-take them, and the test either still passes, which means nothing that matters changed,
# or fails, which is the news.
#
# READ ONLY, DELIBERATELY. Every call below is a GET against the dashboard's own loopback port,
# through `docker exec`, because the API is not published outside the container. Nothing here writes
# to the results tree, and it is safe to run while a sweep is going.
#
#     sh capture.sh
#
# WHAT IS VERBATIM AND WHAT IS A SLICE, because a fixture that has been quietly edited is worse than
# no fixture at all:
#
#   manifest, health, badges     whole responses, byte for byte.
#   bump-detail, bump-detail-queued
#                                whole responses, byte for byte, one bump each.
#   bumps                        A SLICE, and re-indented. The full response is a megabyte of
#                                settlement prose and over a thousand rows built by one piece of
#                                Java, so every row has the same shape and a thousand copies of it
#                                prove nothing a handful do not. The rule is: the smallest row, by
#                                serialised length, for each distinct verdict the corpus is
#                                currently serving. Smallest, because the rows differ in the length
#                                of their prose and in nothing else, and per verdict, so the slice
#                                covers the whole vocabulary in use rather than whatever happens to
#                                sort first.
#
#                                Every FIELD and VALUE in a kept row is exactly what the server
#                                sent. What is not byte-identical is the whitespace and the escaping
#                                of non-ASCII characters, because the slice is written back out by a
#                                JSON encoder so that it can be read and diffed like a file rather
#                                than scrolled like a telegram. Nothing a validator can see is
#                                touched by that; if it ever is, the validator is reading the
#                                encoding rather than the document.
set -eu

cd "$(dirname "$0")"
HERE=$(pwd)

get() { docker exec bjv-dashboard sh -c "wget -qO- 'http://127.0.0.1:8086$1'"; }

get "/.well-known/microfrontend.json" > manifest.json
get "/api/health"                     > health.json
get "/api/badges"                     > badges.json

# The full corpus goes to a scratch file that is not committed; the slice below is.
get "/api/bumps" > /tmp/bjv-bumps-full.json

# IN A CONTAINER, because this host deliberately has no node and no ad-hoc python beside the repo.
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -v "$HERE:/out" \
  -v /tmp/bjv-bumps-full.json:/in/bumps.json:ro \
  python:3-slim python -c '
import json

rows = json.load(open("/in/bumps.json"))
smallest = {}
for row in rows:
    size = len(json.dumps(row))
    kept = smallest.get(row["verdict"])
    if kept is None or size < kept[0]:
        smallest[row["verdict"]] = (size, row)

# In the order the server sent them, so the slice reads like the response it came from.
chosen = [row for row in rows if any(row is kept for _, kept in smallest.values())]
json.dump(chosen, open("/out/bumps.json", "w"), indent=1)
open("/out/bumps.json", "a").write("\n")
print("kept %d of %d rows, one per verdict: %s"
      % (len(chosen), len(rows), ", ".join(sorted(smallest))))
'

# ONE SETTLED BUMP AND ONE THAT HAS NOT STARTED, which are the two ends of the record: every event
# kind the pipeline emits, and none at all. The slugs are pinned rather than picked, because a
# fixture whose subject changes every time it is re-taken cannot be diffed against the last one.
get "/api/bump?slug=AgitoReiKen_ForgeHaxEx_d96d3cfaa3c7d0626f9aa74e32fdbf015396ae99_8_11" \
  > bump-detail.json
get "/api/bump?slug=onap_cps_67973be1deea7de52b750e9bdd6dc53da265da65_17_21" \
  > bump-detail-queued.json

rm -f /tmp/bjv-bumps-full.json
echo "captured into $HERE:"
wc -c ./*.json
