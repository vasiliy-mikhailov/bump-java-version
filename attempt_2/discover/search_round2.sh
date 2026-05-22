#!/usr/bin/env bash
# Round 2 — target Java 11/17 + each family explicitly via version-anchored queries.
set -euo pipefail
OUT=attempt_2/discover/raw
mkdir -p "$OUT"

declare -A Q=(
  # Boot 2.5–2.7 require Java 11+; 2.7 also OK on 17. Filter pushed since 2022.
  [sb2-j11-a]='spring-boot-starter-parent 2.7 in:file filename:pom.xml language:Maven\ POM pushed:>2022-01-01'
  [sb2-j11-b]='"<java.version>11" "spring-boot" in:file filename:pom.xml'
  [sb2-j17-a]='"<java.version>17" "spring-boot" "2." in:file filename:pom.xml'
  # JUnit4 + Java 11/17 — modern projects still using junit4
  [jun-j11]='"junit:junit:4" "<java.version>11" in:file filename:pom.xml'
  [jun-j17]='"junit:junit:4" "<java.version>17" in:file filename:pom.xml'
  # Hibernate 5 + Java 11/17 — direct usage
  [hib-j11]='"hibernate-core" "5." "<java.version>11" in:file filename:pom.xml'
  [hib-j17]='"hibernate-core" "5." "<java.version>17" in:file filename:pom.xml'
  # Jakarta/javax + Java 11/17
  [jak-j11]='"javax.persistence" "<java.version>11" in:file filename:pom.xml'
  [jak-j17]='"javax.persistence" "<java.version>17" in:file filename:pom.xml'
)

for key in "${!Q[@]}"; do
  echo ">>> $key  : ${Q[$key]}"
  for page in 1 2 3; do
    gh api -X GET /search/code -f q="${Q[$key]}" -F per_page=100 -F page=$page \
       > "$OUT/code-${key}-p${page}.json" 2>/dev/null || { echo "  page $page failed"; break; }
    sleep 7  # code search auth limit = 30/min
    n=$(jq '.items | length' "$OUT/code-${key}-p${page}.json" 2>/dev/null || echo 0)
    echo "  page $page → $n hits"
    [ "$n" -lt "100" ] && break
  done
done
