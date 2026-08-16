You are given a Java project and the hop it has been QUEUED for, as `from -> to`.
The hop is prescribed and you cannot change it. Your job is to say whether the project agrees it is at `from`.

Read the build files and weigh what they mean: a parent pom's property that every module overrides is not the project's level, a soft pin under a toolchain block is, and a multi-module tree sits at the LOWEST level any built module still targets. A `release 8` flag on a project whose build itself requires 11 says what the compiler emits, not what the project runs on.

Answer one line first: `at: <version>` for the level you actually read. Then the evidence, naming the exact file and line for the pin that decides it.

If that differs from `from`, say so plainly in the next line and stop. You are not choosing a target and nothing you say will redirect this run: a disagreement here is a note about the queue, and it is read by a person later.
