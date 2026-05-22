#!/usr/bin/env bash
# Cast a wide net via GitHub repo search. Discovery is cheap — verification is the real filter.
set -euo pipefail
OUT=attempt_2/discover/raw
mkdir -p "$OUT"

# Each query string targets a family. We accept that some hits will be Java 8/11/17 and detect later.
# size in KB; 8000 KB ≈ small projects (avoid 100MB megarepos)
declare -A Q=(
  # spring-boot-2: explicit version marker + topic
  [sb2-a]="topic:spring-boot language:java size:<6000"
  [sb2-b]="spring-boot-starter-parent in:file filename:pom.xml"
  [sb2-c]="topic:spring-boot-2 language:java size:<8000"
  # jakarta/javax: jakarta or javax namespace markers
  [jak-a]="topic:jakarta-ee language:java size:<6000"
  [jak-b]="topic:javaee language:java size:<6000"
  [jak-c]="topic:jpa language:java size:<6000"
  # junit4-mockito: testing-heavy small projects
  [jun-a]="topic:junit4 language:java size:<6000"
  [jun-b]="topic:mockito language:java size:<6000"
  # hibernate-5: direct hibernate usage
  [hib-a]="topic:hibernate language:java size:<6000"
  [hib-b]="topic:hibernate5 language:java size:<6000"
)

for key in "${!Q[@]}"; do
  echo ">>> $key  : ${Q[$key]}"
  # Each query yields up to 100 per page; pull 3 pages = 300 candidates max per query.
  for page in 1 2 3; do
    gh api -X GET /search/repositories \
       -f q="${Q[$key]}" \
       -F per_page=100 \
       -F page=$page \
       > "$OUT/${key}-p${page}.json" 2>/dev/null || { echo "  page $page failed"; break; }
    sleep 3  # repo search auth rate-limit is 30/min
    n=$(jq ".items | length" "$OUT/${key}-p${page}.json")
    echo "  page $page → $n repos"
    [ "$n" -lt "100" ] && break
  done
done
echo
echo "=== summary ==="
for f in "$OUT"/*.json; do
  n=$(jq ".items | length" "$f")
  echo "  $(basename "$f"): $n"
done
