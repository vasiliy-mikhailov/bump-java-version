# bump-java-version

Moves a Java project from one LTS to the next, and says honestly whether it worked.

Give it `repo|sha|from|to`. It clones the repository, establishes what passes under the
project's own JDK, walks the modules raising what the hop needs, moves the JDK, compiles,
repairs what broke, hardens what the scanner found, and settles on a verdict backed by a
build it ran rather than by anything an agent said about one.

A green gate means the project builds under the target and lost no test. That is deliberately
not the same claim as "the bump is good", which is why compliance against a bill of materials
is measured and reported separately.

## The shape

The program is a tree of agents, and this is the whole of it. Every stage is a planner, a
doer and a verifier, and the verifier holds the loop: it answers `done`, `again` or `replan`,
so a retry is a question asked inside a stage rather than an arrow drawn between two.

```
survey                                     does the project agree it is where the manifest says
baseline                                   what passes under the project's own JDK
security-before                            what it is carrying now
module-filter                              which modules this bump works on
modules                          per module
    module
        platform                           spring-boot, quarkus, or nothing manages this module
        before-pins                        what javac cannot start without
        bump                               move the JDK
        module-gate                        compile; until green or the turns run out
            module-repair                  only when the gate is red
                module-repair-step
        after-pins                         everything raised once the module compiles
gate                                       compile AND run the tests, for the repository
security-after                             only after a green gate
estimator                                  what the work would have cost a person
verdict                                    only when the gate never went green
```

That text is not a diagram kept beside the code. `Shape.of` walks the same object the runtime
executes, so a stage cannot be advertised after it is deleted. `Flow` is the three combinators
it is built from: in order, once per item, until a condition clears.

Fourteen of the agents exist once per dependency-management platform, named
`before-pins-doer@spring-boot` and so on, because raising a version on a module Spring Boot
manages and on a module nothing manages are different jobs with opposite worst moves.

## Layout

```
agent/        the pipeline and the dashboard server; the client is under agent/ui
hoptools/     jvm-run and jvmjob, the sealed runner a lane executes builds through
Dockerfile    builds bjv-alljdk, the sandbox image jvm-run runs
scripts/      baked into that image at /opt/scripts
corpus/       discovery lists, how new repositories enter the corpus
runs/         traces and workspaces, gitignored
proxy/        routing
```

## Running it

```
cd agent
./deploy.sh                                   # build the jar and the image, restart the dashboard
tmux new-session -d -s bjv 'env LANES=8 bash ./run.sh <manifest.tsv>'
bash ./rerun.sh &                             # drains what the dashboard rerun button asks for
```

`agent/.env` holds the paths and the keys; `.env.example` lists what it needs. A lane keeps
the image it started with, so a deploy mid-sweep leaves two generations running at once.

## Which pipeline produced a result

Every settled bump records the commit, the image, and hashes of the prompts and the bills of
materials it was actually handed. A sweep runs for a fortnight and this changes daily, so
without that a bump that disagrees with another cannot be told from a pipeline that disagrees
with another. The prompt and list hashes are there because edits live in a store outside the
image: a commit alone would call two runs the same pipeline exactly when one had been edited
from the settings page.

## The dashboard

Served from the same jar. It shows the corpus, what each bump settled on and why, the CVEs
before and after, compliance against the bill of materials for the hop, and which pipeline
produced each row. The settings page edits the prompts and the lists, per hop, and an edit
takes effect on the next lane to start rather than on the ones already running.
