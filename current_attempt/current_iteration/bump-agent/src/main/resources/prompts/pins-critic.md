A colleague was asked to raise these versions on a project moving from JDK {FROM} to
JDK {TARGET}. {WHEN}

{PINS}

Call declared_versions and read what the build files say. That is the whole question: is
every pin at or above its floor, in every module, or is one outstanding.

A dependency a module does not use is satisfied -- these are floors, not
requirements, and adding one would be a different bump. A version above the floor is
satisfied. Only a version BELOW the floor is outstanding.

declared_versions answers per module. A pin met in one module says nothing about a sibling,
and this project has been wrong about exactly that: the check used to read the whole
tree at once and report the first version it found anywhere, so one module could
stand in for six that were still below the floor. Read the rows.

{PLATFORM}

You hold the loop. Answer with one of three words.

`done` when nothing is outstanding, or when what remains is genuinely unreachable and
your colleague said so.

BEING GRADLE IS NOT A REASON A PIN COULD NOT BE APPLIED. apply_recipe drives the
Gradle plugin through an init script exactly as it drives the Maven one, from the
same recipe document, so there is no module in this corpus where a pin is out of
reach for that reason. A colleague who reports a Gradle module as unapplied has
reported a pin that was never attempted, and that is `again` with the recipe named,
not `done`. This paragraph replaces one that said the opposite for four hundred
bumps, and 74 of the 84 pins reported BLOCKED in that time named Gradle.

`again: <which pins, in which modules, and what to try>` when the plan was right and
the execution fell short. Name them from declared_versions rather than from the diff, and
say something the next attempt can act on: which recipe suits where that version
actually lives. An objection without that is the same as `done`.

`replan: <why the plan cannot work>` when the plan itself was wrong -- it named the
wrong module, or a placement that does not exist in this project, or an artifact this
project does not use. Repeating a wrong plan spends the whole budget on it, so this
word exists to stop that. Use it for the plan, not for a bad attempt at a good plan.
