package tech.mikhailov.bjv.agent;

/**
 * FOUR PRODUCER/CRITIC PAIRS AND TWO CLOSERS, each with its own closed set of answers.
 *
 * <p>There is no orchestrator: an agent asked to follow an order it can rewrite will rewrite it.
 * {@link Bump} runs the order — survey, prepare, bump, troubleshoot — and every producer's work is
 * judged by its own critic before the chain moves, because the expensive mistake at each phase is
 * different and a single reviewer prompted for everything reviews nothing well.
 *
 * <p>PRODUCERS WRITE, CRITICS JUDGE. A producer's output is checked by the compiler and the gate,
 * so it speaks EDIT blocks that {@link Edits} applies mechanically — and never to a test, which
 * {@code Edits} enforces rather than requests. A critic's answer is BRANCHED ON, so it gets the
 * record and a word list. An unreachable critic waives (the work stands); the empty answer routes
 * to the word list's default.
 *
 * <p>THE KNOWLEDGE LIVES IN THE PROMPTS, and the prompts live here, in the code, because a prompt
 * and the loop that branches on its answer are one design. The skill document is DERIVED from this
 * file and the chain's order, not the other way round — and every (prompt, reply) pair lands in the
 * trace in full, so the feedback filed against a reply is a labelled complaint about a prompt anyone
 * can find.
 */
final class Agents {

    @FunctionalInterface
    interface Agent {
        String run(String task);
    }

    private final Llm llm;
    private final Trace trace;

    Agents(Llm llm, Trace trace) {
        this.llm = llm;
        this.trace = trace;
    }

    // ---- pair 1: which hop is this, actually ----

    /** Reads the build files and names the hop. The deterministic detector's guess travels along. */
    Agent surveyor() {
        return agent("surveyor", """
                You determine what Java level a project is REALLY on, and therefore which one-LTS hop \
                it should take (8->11, 11->17, 17->21 or 21->25).

                You are given the build files and a grep of every version pin in the tree, plus the \
                deterministic detector's guess. The detector reads declarations; you also weigh what \
                they mean: a parent pom's property that every module overrides is not the project's \
                level, a soft pin under a toolchain block is, and a multi-module tree sits at the \
                LOWEST level any built module still targets.

                Answer with one line first: `hop: <from>-><to>`. Then the evidence, naming the exact \
                file and line for the pin that decides it. If the project is not bumpable (already at \
                the top, or not a Java build), answer `hop: none` and say why.
                """);
    }

    /** Checks the named hop against the same files. Objects only with a correction in hand. */
    Agent surveyCritic() {
        return agent("survey-critic", """
                A colleague named the hop for a Java version bump. Judge the CLAIM against the same \
                evidence you are both given.

                The expensive mistakes: reading a parent's property when the modules override it, \
                reading the newest pin in a tree whose oldest module decides the level, and calling a \
                project bumpable when its build is not a Java build at all.

                Answer `sound` when the hop stands. Answer `wrong-hop: <from>-><to>` with the file \
                and line that proves it when it does not. If you cannot name the pin that refutes \
                them, answer `sound` — an objection without a correction is the same as approving.
                """);
    }

    // ---- pair 2: the hop's proactive steps ----

    /**
     * Executes the hop's proactive steps: the structure-gated moves that must land BEFORE the first
     * target build, where the deterministic pre-pass could not (Gradle DSL edits, module-local pins,
     * judgement about which trigger actually fired). The knowledge is the prompt; a skill document
     * can later be derived from it.
     */
    Agent preparer() {
        return agent("preparer", """
                You prepare a Java project for a one-LTS migration BEFORE its first target build. \
                Every step is gated on a structural trigger; check each trigger against the build \
                files you are given and land the step only where it fires. A deterministic pre-pass \
                has already run: what it did travels in the brief, do not redo it.

                The steps, by trigger (versions are measured floors, not folklore):
                - project resolves Lombok (declared anywhere, or transitively): floor it to 1.18.30 \
                (1.18.46 when the target is 25, plus the maven.compiler.proc=full property, since \
                JDK 23+ no longer auto-runs classpath processors). When a Spring BOM arrives with \
                scope=import, a property override is a silent no-op: use a dependencyManagement entry.
                - build tool is Gradle and the wrapper is below the target JDK's floor (7.6 for 17, \
                8.10.2 for 21, 9.1.0 for 25): set distributionUrl, and keep gradlew executable.
                - project declares JaCoCo: floor it to 0.8.15, in the module that declares it.
                - project mocks (mockito/byte-buddy/MockK): force byte-buddy 1.14.12 (1.17.6 at \
                target 25) and mockito-core 5.18.0.
                - Kotlin build: kotlin 2.3.20 when the target is 25; every 1.x fails there.
                - a test dependency reflects into the process environment (junit-pioneer, \
                system-lambda, system-rules): add --add-opens java.base/java.util and java.base/java.lang \
                to every test fork, at the root so sibling modules are covered.

                Answer with EDIT blocks only, in this exact format, nothing between them:

                EDIT <path relative to the repo root>
                <<<<
                <exact text currently in the file, enough lines to be unique>
                ====
                <the replacement text>
                >>>>

                After the last block, one line: DID: <which proactive steps you executed and which \
                triggers did not fire>. If every trigger is already satisfied, answer exactly \
                NOTHING-TO-DO: <why>. Never edit a test; the harness rejects it.
                """);
    }

    /** Judges the preparation against the same trigger list the preparer carries. */
    Agent prepareCritic() {
        return agent("prepare-critic", """
                A colleague prepared a Java project for a one-LTS migration: version floors and \
                wrapper moves gated on structural triggers (Lombok resolved, wrapper below the \
                target's floor, JaCoCo declared, mocking present, Kotlin at target 25, env-mutating \
                test libs). The edits and the build files travel in the brief. Judge TWO things.

                MISSED: a trigger fires on these build files and no edit answers it. Name the step \
                and the file that proves the trigger fired.

                OVERREACH: an edit answers no trigger — the skill did not ask for it, or its trigger \
                does not fire here. Structure-gated means gated: a step applied "just in case" is how \
                a working build gets broken by its own preparation.

                Answer `sound`, or `missed: <step>` or `overreach: <edit>`, one finding per line, the \
                most damaging first.
                """);
    }

    // ---- pair 3: land the target ----

    /**
     * Lands the effective bytecode target after the recipes ran: the pins the recipe under-applied,
     * module-local shadows, the DSL variants. "Green build" and "target landed" are different facts
     * and only the second one scores.
     */
    Agent bumper() {
        return agent("bumper", """
                A migration recipe has run, and a deterministic sweep has raised what it could. Your \
                job is what is LEFT: every version pin, toolchain block, property or compiler flag \
                still below the target in ANY module, in whichever dialect this build speaks. The \
                gate measures the MINIMUM class-file major across every compiled main class \
                (55 for 11, 61 for 17, 65 for 21, 69 for 25), so one unraised module fails the whole \
                bump silently: a green build is not proof, the grep below is.

                You are given the grep of every remaining pin below target. Answer with EDIT blocks \
                (same format as always) that raise each one, or exactly NOTHING-TO-DO: <why> when \
                the grep is genuinely clean. A green build is not the goal; the landed target is. \
                Never edit a test.
                """);
    }

    /** Checks the landing against the gate's definition of landed. */
    Agent bumpCritic() {
        return agent("bump-critic", """
                A colleague raised a project's remaining Java target pins. Judge ONE question: after \
                these edits, will the effective bytecode target actually reach the hop's target, by \
                the gate's definition in the brief?

                The expensive mistakes: a module-local property that shadows the fixed parent, a \
                second pin in the same file (a toolchain block AND an options.release), and an edit \
                that raises a pin the build never reads.

                Answer `sound`, or `not-landed: <file and pin still below target>`, or \
                `overreach: <edit that changes something other than a target pin>`.
                """);
    }

    // ---- pair 4: the residue the wall table does not know ----

    /** Clears the wall no signature matched: the residue a model is FOR. */
    Agent troubleshooter() {
        return agent("troubleshooter", """
                You are the reflect loop's residue handler: the deterministic wall table recognised \
                nothing in this failure. Known wall families, for orientation only (the table \
                already tried their exact signatures): removed JDK APIs, strong encapsulation, \
                bytecode-reading tools too old for the new class-file major, annotation processors \
                silently disabled, JUnit 4 to 5 fallout. The failure may be a variant, or new.

                Diagnose the FIRST real error in the log, then propose the SMALLEST edit that clears \
                it. Answer with EDIT blocks (same format as always), then one line: \
                WHY: <the wall and why this clears it>.

                Rules the harness enforces: no test edits, no new files. If the wall cannot be \
                cleared within those rules — a dependency with no compatible version, a target-JDK \
                behaviour change only a test edit could absorb — answer exactly BLOCKED: <why>. That \
                is a useful answer; a speculative edit is not.
                """);
    }

    /** Judges the troubleshooting edit: migration fix, or gaming the gate? */
    Agent troubleCritic() {
        return agent("trouble-critic", """
                A colleague proposed an edit to get a migration past its build gate. Judge ONE \
                question: is this a migration fix, or does it game the gate?

                Gaming looks like: weakening or skipping tests by configuration, deleting \
                functionality instead of migrating it, pinning a tool to a version that ignores the \
                new bytecode rather than reading it, or editing generated output instead of its \
                source.

                Answer `sound` when it is a real migration step a maintainer would keep. Answer \
                `gaming` and name the exact line when it is not. Answer `off-target` when the edit is \
                honest but aims at the wrong wall: say which wall the log actually shows.
                """);
    }

    // ---- the closers ----

    /** Argues only what execution could not settle. */
    Agent verdict() {
        return agent("verdict", """
                A Java version bump ended without the gate establishing a verdict. You argue what \
                this bump IS, from the record you are given.

                `blocked-dependency`  — a dependency has no version compatible with the target JDK. \
                Name it and say what was tried.
                `behavior-change`     — the target JDK changed observable behaviour and only a test \
                edit could reconcile it, which the rules forbid. Name the exact change.
                `infra`               — the environment failed the bump: resolution, timeouts, disk. \
                A tooling failure must not read as a migration failure.

                One word first, then the argument.
                """);
    }

    /** Prices the attempt from the record. */
    Agent estimator() {
        return agent("estimator", """
                You read a completed attempt to bump a Java project one LTS step, and estimate what \
                the same work would have cost a competent Java developer who had not seen this code \
                before. Charge the work actually done, not the outcome, and charge the dead ends. \
                Answer with ONE line first: `minutes: N`. Then three to six lines itemising.
                """);
    }

    private Agent agent(String name, String prompt) {
        return task -> {
            String reply;
            try {
                reply = llm.ask(prompt, task);
            } catch (RuntimeException e) {
                reply = "";
                trace.progress("", name + " unreachable: " + e.getMessage());
            }
            reply = reply == null ? "" : reply;
            trace.asked(name, prompt + "\n\n---\n\n" + task, reply);
            return reply;
        };
    }
}
