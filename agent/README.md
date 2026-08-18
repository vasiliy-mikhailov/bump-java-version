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

## The modules

```
agent/           the Maven parent: every version is decided here, nothing is built here
  engine/        the agent loop, the model wiring, the trace and the JSONL record it is
                 written as. Depends on no other module
  jvm/           what a JVM project is: the module tree, a build under a chosen JDK, the
                 bytecode majors it actually reached, gradle distributions, jars.
                 Depends on engine
  app/           this pipeline: the bump chain with its prompts and BOM tables, and the
                 dashboard that reads the record. Depends on both, and is the only module
                 that shades
```

The split is so that **engine and jvm can leave**: neither depends on anything above it, so
another pipeline can take them as jars without taking the bump with them. `bump` and `web`
are one module because they are both this pipeline, and a fourth module would buy nothing.

```
mvn -B -o test       # every module, one reactor pass, per-module totals that add up
mvn -B -o package    # -> app/target/bump-agent-0.1.0-SNAPSHOT.jar
```

Both run from `agent/`. The jar path is written down twice, in `Dockerfile` (which `COPY`s
it rather than building it) and in `deploy.sh` (which ships it). They move together, or the
image reuses the cached layer and ships the previous jar while reporting success.

## Running on an internal VM

Compose does **not** migrate anything by itself. `dashboard` reads `results/`. `run.sh` (or the
`launcher` profile) clones the manifest and starts `Bump`. Each bump then spawns `bjv-alljdk`
through the host docker socket.

```
cp .env.example .env          # fill every required value — no author-host defaults
cp -a ../hoptools "$BJV_HOPTOOLS"
cp config/settings.xml.example "$BJV_SETTINGS"
cp config/init.gradle.example "$BJV_GRADLE_INIT"
mkdir -p "$BJV_RUNROOT" "$BJV_M2" "$BJV_GRADLE_DISTS"
# attach the Maven proxy container to docker network $BJV_NET (default mvn-cache)
bash ./smoke.sh               # images, env, network, paths
docker compose up -d          # dashboard at http://127.0.0.1:8086
docker compose --profile batch run --rm launcher
```

Supervisor (needs `OC_KEY`): `docker compose --profile supervisor up -d`.

Same image, one bump, docker socket required — paths are **host** paths, bind-mounted at the same
absolute location so nested `docker run -v $BJV_WS:/work` hits the checkout:

```
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$WS:$WS" -v "$BJV_HOPTOOLS:$BJV_HOPTOOLS:ro" \
  -e OC_KEY -e OC_BASE -e OC_MODEL -e BJV_HOPTOOLS -e BJV_JDK_IMAGE \
  "$BJV_IMAGE" tech.mikhailov.bjv.bump.Bump \
  "$WS" 'group/project|sha|11|17' "$RESULTS"
```

Manifest TSV: `slug  group/project  sha  from  to`. One row is one LTS hop. 8→21 is three hops.
Clone URL is `$GIT_BASE/$repo.git`. HTTPS: `GIT_TOKEN`. SSH: `GIT_SSH_KEY` (run `run.sh` on the
host, or add a same-path volume for the key to the launcher).

`BJV_IMAGE` is the agent. `BJV_JDK_IMAGE` is the all-JDK sandbox. They are different names on
purpose — do not reuse `BJV_IMAGE` for builds.

`BJV_THINKING=false` if the LLM is not Qwen and rejects `chat_template_kwargs.enable_thinking`.
The endpoint still needs tool-calling.

## Images

**bjv-agent.** Fat jar. Rebuild needs `com.deepagents:langchain4j-deepagents` in a local Maven
repo. First copy: retag Hub `vasiliymikhailov/bjv-agent` or `docker load` a save from the research
host, then `docker push $BJV_IMAGE`.

**bjv-alljdk.** Produced by the `current_sweep/` Dockerfile chain plus
`current_iteration/Dockerfile.alljdk`.

## Maven, Gradle, LLM, Git

- Put the proxy's **container DNS name** in `config/settings.xml.example` → `$BJV_SETTINGS`.
  A host IP from inside `bjv-alljdk` is the wrong address.
- Join the proxy to docker network `$BJV_NET` (Compose creates `mvn-cache` if it does not exist).
- Gradle: `$BJV_GRADLE_INIT` (see `config/init.gradle.example`) and pre-stage wrapper zips under
  `$BJV_GRADLE_DISTS` — sealed builds do not download distributions.
- `OC_BASE` / `OC_KEY` / `OC_MODEL` must reach an OpenAI-compatible chat endpoint with tool-calling.
  Missing `OC_KEY` must fail visibly, not look like an empty-supervisor "nothing is wrong".

## Smoke

`bash ./smoke.sh` checks env, images, hoptools, settings, and the docker network.

`bash ./smoke.sh --run` then executes `run.sh` against `$BJV_MANIFEST`.

Ready means: `$BJV_RUNROOT/results/settlements.jsonl` has a terminal `PASS` for that hop, and the
dashboard shows `results/<slug>/trace.jsonl`. A green `compose up` with an empty queue is not a
migration.

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
