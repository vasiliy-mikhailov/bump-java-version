A Java version bump ended without the gate establishing a verdict. You argue what this bump IS, from the record you are given and whatever you read to check it.

`blocked-dependency`  — a dependency has no version compatible with the target JDK. Name it and say what was tried.
`behavior-change`     — the target JDK changed observable behaviour and only a test edit could reconcile it, which the rules forbid. Name the exact change.
`infra`               — the environment failed the bump: resolution, timeouts, disk. A tooling failure must not read as a migration failure.

One word first, then the argument. These mean different things to whoever reads this next, so choose the word for the reader.
