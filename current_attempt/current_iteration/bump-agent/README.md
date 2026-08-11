# bump-agent

One Java LTS bump, run as a fixed-order chain with a full trace, ported from the
fix-java-svace-markers harness. The order is Java, not a paragraph an agent can rewrite.

```
mvn -q package
OC_BASE=… OC_KEY=… OC_MODEL=… \
  java -cp target/bump-agent-0.1.0-SNAPSHOT.jar tech.mikhailov.bjv.agent.Bump \
  <checkout> 'repo|sha|from|to' results ./migrate.sh

java -cp target/bump-agent-0.1.0-SNAPSHOT.jar tech.mikhailov.bjv.agent.Dashboard results 8086
```

## The chain

```
baseline (from-JDK)  → migrate.sh (recipes + floors + target propagation, deterministic)
  → reflect loop, up to 8 turns:
      gate (to-JDK build+test) → green? done
      walls: the mechanized troubleshooting table    (free, deterministic, evidence-backed)
      fixer → fix-critic                             (the residue no table row recognises)
  → settle: computed from the builds where they decided; the verdict agent argues only
            what execution could not settle. estimator prices the attempt from the record.
```

**The gate is the arbiter.** No agent can invoke the runner; whether the gate ran after an
edit is not a model's choice. The fixer may never touch a test — enforced in `Edits`, not
in the prompt, because a rule the model can rewrite is a suggestion.

**Free things first.** Every loop turn tries the wall table before spending a model call.
The table is the enumerable half of the reflect loop rung-1 measured (roughly half of what
iteration recovers); the fixer is only for what no signature matches.

## The record

- `results/<slug>/trace.jsonl` — everything, in order: every `(prompt, reply)` pair in
  full, every build, every deterministic fix. The unit prompt tuning replays.
- `results/settlements.jsonl` — the last word per bump, append-only.
- `feedback/feedback.jsonl` — a person's judgement of ONE reply, filed from the dashboard,
  carrying the prompt and reply so a row is a self-contained training example.

Facts (`built`, `applied`) and opinions (`asked`) are distinct kinds in the trace: a reader
who cannot tell which of the two decided a settlement cannot audit it.
