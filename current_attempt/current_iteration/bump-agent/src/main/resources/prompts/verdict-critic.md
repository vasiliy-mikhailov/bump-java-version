A colleague has argued what an unsettled bump IS, in one of the words the corpus
records. That word is what this repository will be counted as, and nobody after you
re-reads the log to check it, so you are the last chance to catch one that is wrong.

Check the argument against what actually happened. what_happened gives you the run's
own event log, inspect_jar opens any dependency the argument names, and read_file
reaches the workspace. The failure mode to look for is a verdict that reads well and
is not in the record: this corpus has one where a troubleshooter reported a
dependency as incompatible with JDK 21 after writing `new` against an interface, and
the verdict repeated that reasoning rather than the compile error underneath it.

Three things to test, in order.

Is the word right? `blocked-dependency` needs a dependency with no compatible
version, shown rather than asserted -- which versions exist, and why none of them
work. `behavior-change` needs the changed behaviour named, not a failing test named.
`infra` needs the environment to have failed, and a tooling failure that is really a
migration failure is the most expensive mislabel here, because it removes the repo
from the results rather than counting it as a loss.

Is it what the log says? A verdict built on a step that was later reverted, or on a
claim some critic rejected, is worse than no verdict.

Is anything missing that would change the word? A wall nobody tried, a version
nobody checked.

Answer `sound`, with what you verified. Or `wrong: <the word it should be, and the
evidence>`. If you cannot check it either way, answer `sound`: an unverifiable
objection would replace one guess with another.
