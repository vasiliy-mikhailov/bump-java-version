# bump-agent

One Java LTS bump, run as a fixed-order chain of producer/critic pairs with a full trace.
Ported from the fix-java-svace-markers harness; the agents are deepagents `SubAgentRuntime`
instances with per-role tools, and the order is Java, not a paragraph an agent can rewrite.

## The chain

```
surveyor ──→ survey-critic        which hop is this, actually   (wrong-hop → adopt correction)
   ↓
baseline @ from-JDK               a FACT; no green baseline, no bump. The passing-test SET is
                                  taken here — conservation is which tests passed, not how many
   ↓
Migrate (deterministic)           recipes chosen by the project's own Boot line, measured
                                  version floors, target sweep over every pom
   ↓
preparer ──→ prepare-critic       the proactive steps            (missed|overreach → once more)
   ↓
bumper ──→ bump-critic            land the effective target      (not-landed → once more)
   ↓
reflect loop, ≤ 8 turns:
  Gate @ to-JDK                   builds + conserves + target landed → PASS, no model involved
  Walls                           the enumerable troubleshooting rows, free, before any model call
  troubleshooter ──→ trouble-critic  the residue                 (gaming → revert and stop)
   ↓
verdict                           argues ONLY what execution could not settle
estimator                         prices the attempt from the record
```

**The gate is the arbiter.** Producers may try their own build and what they learn is feedback;
the build that DECIDES runs between the stages, because whether the gate ran after an edit is not
a model's choice. `Gate` is the corpus's own scorer: builds under the target, every test that
passed still passes, and the minimum main-class bytecode major actually reached the target. A
green build proves only the first.

**Roles are enforced by tools, not by prose.** Producers get `edit_file` and `try_build`; critics
get `read_file`, `grep`, `glob`, because a certification must not manufacture the evidence it
certifies. Neither gets `write_file`. An edit under a test source root is refused at the executor.

**Free things first.** Every loop turn tries `Walls` before spending a model call.

## Running

```
cp .env.example .env          # set BJV_DASH_TOKEN (required)
docker compose up -d          # dashboard at http://127.0.0.1:8086
```

Pulls [`vasiliymikhailov/bjv-agent`](https://hub.docker.com/r/vasiliymikhailov/bjv-agent) from Docker Hub.

Supervisor (needs `OC_KEY`): `docker compose --profile supervisor up -d`.

One bump, same image, docker socket required:

```
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$WS:$WS" -e OC_KEY=… -e BJV_HOPTOOLS=…/hoptools \
  vasiliymikhailov/bjv-agent "$WS" 'owner/repo|sha|11|17' "$RESULTS"
```

## The record

- `results/<slug>/trace.jsonl` — everything in order: every `(prompt, reply)` pair and every tool
  call in full, plus the builds. The unit prompt tuning replays.
- `results/settlements.jsonl` — the last word per bump, append-only.
- `results/feedback/feedback.jsonl` — a person's judgement of ONE reply, filed from the dashboard,
  carrying the prompt and reply so a row is a self-contained training example.

Facts (`built`, `applied`, `tool`) and opinions (`asked`) are distinct kinds: a reader who cannot
tell which of the two decided a settlement cannot audit it.

## The dashboard

Served publicly, so it is written for that: bump keys are matched against an allowlist and the
resolved path re-checked (a slug is an opaque key, never a path), the feedback write is bounded
and token-gated via `BJV_DASH_TOKEN`, and every rendered value is escaped — traces carry model
output and third-party repository source.
