package tech.mikhailov.bjv.agent;

import java.nio.file.Path;
import java.util.Map;

import com.deepagents.langchain4j.logging.ToolInvocationLogMode;
import com.deepagents.langchain4j.subagents.SubAgentRuntime;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * FOUR PRODUCER/CRITIC PAIRS AND TWO CLOSERS, each with its own closed set of answers and its own
 * closed set of tools.
 *
 * <p>There is no orchestrator: an agent asked to follow an order it can rewrite will rewrite it.
 * {@link Bump} runs the order — survey, prepare, bump, troubleshoot — and every producer's work is
 * judged by its own critic before the chain moves, because the expensive mistake at each phase is
 * different and a single reviewer prompted for everything reviews nothing well.
 *
 * <p>PRODUCERS EDIT, CRITICS READ, AND THE SPLIT DECIDES THE TOOLS. A producer reaches the
 * workspace through {@code edit_file} and can try its own build; a critic gets {@code read_file},
 * grep and glob, because a certification must not manufacture the evidence it certifies. Neither
 * gets {@code write_file}: a new file is not a migration step. The rule that a test may never be
 * edited is enforced in {@link Tools}, at the executor, not requested in prose here.
 *
 * <p>THE KNOWLEDGE LIVES IN THE PROMPTS, and the prompts live here, in the code, because a prompt
 * and the loop that branches on its answer are one design. The skill document is DERIVED from this
 * file and the chain's order, not the other way round — and every (prompt, reply) pair and every
 * tool call lands in the trace in full, so feedback filed against a reply is a labelled complaint
 * about a prompt anyone can find.
 */
final class Agents {

    @FunctionalInterface
    interface Agent {
        String run(String task);
    }

    private final ChatModel model;
    private final ChatModel judging;
    private final Path ws;
    private final Runner runner;
    private final Trace trace;
    private final String targetJdk;

    Agents(ChatModel model, Path ws, Runner runner, String targetJdk, Trace trace) {
        this(model, Model.forCritic(trace), ws, runner, targetJdk, trace);
    }

    Agents(ChatModel model, ChatModel judging, Path ws, Runner runner, String targetJdk,
           Trace trace) {
        this.judging = judging;
        this.model = model;
        this.ws = ws;
        this.runner = runner;
        this.targetJdk = targetJdk;
        this.trace = trace;
    }

    // ---- pair 1: which hop is this, actually ----

    /** Reads the build files and names the hop. The deterministic detector's guess travels along. */
    Agent surveyor() {
        return runtime("surveyor", read("surveyor"), """
                You are given a Java project and the hop it has been QUEUED for, as `from -> to`.
                The hop is prescribed and you cannot change it. Your job is to say whether the \
                project agrees it is at `from`.

                Read the build files and weigh what they mean: a parent pom's property that every \
                module overrides is not the project's level, a soft pin under a toolchain block is, \
                and a multi-module tree sits at the LOWEST level any built module still targets. A \
                `release 8` flag on a project whose build itself requires 11 says what the compiler \
                emits, not what the project runs on.

                Answer one line first: `at: <version>` for the level you actually read. Then the \
                evidence, naming the exact file and line for the pin that decides it.

                If that differs from `from`, say so plainly in the next line and stop. You are not \
                choosing a target and nothing you say will redirect this run: a disagreement here \
                is a note about the queue, and it is read by a person later.
                """);
    }

    /** Checks the reading against the same tree. Objects only with a correction in hand. */
    Agent surveyCritic() {
        return runtime("survey-critic", read("survey-critic"), """
                A colleague read which Java level a project is really on. The hop itself is \
                prescribed elsewhere and is not in question. Judge the READING.

                The expensive mistakes: taking a parent's property when the modules override it, \
                taking the newest pin in a tree whose oldest module decides the level, and reading \
                a compiler `release` flag as the level the build itself requires.

                Answer `sound`, or `reads-as: <version>` with the file and line that proves it. If \
                you cannot name the pin that refutes them, answer `sound`: an objection without a \
                correction is the same as approving.
                """);
    }

    // ---- pair 2: what the project is carrying, before anything touches it ----

    /**
     * Reads the pre-bump scan and says which of it this hop could plausibly clear.
     *
     * <p>ADVISORY, AND IT HAS NO TOOL THAT WRITES. Nothing downstream lifts a dependency for
     * security: the preparer works a closed trigger list, the bumper raises target pins, the
     * troubleshooter clears the wall in the log. An edit made here would be charged by the reward
     * and flagged by the prepare-critic as answering no trigger, and a dependency lifted before the
     * first target build is the highest-variance edit in this system. So this stage produces a
     * READING, recorded for whoever decides how a CVE count and a PASS trade against each other.
     */
    Agent securityBefore() {
        return runtime("security-before", read("security-before"), """
                You are handed a vulnerability scan of a Java project taken BEFORE any migration \
                work, counting CRITICAL and HIGH only. Say what it means for the one-LTS hop this \
                project is about to take.

                Answer three things, briefly:
                REACHABLE:   which of the worst packages a version bump of this hop would plausibly \
                lift on its own, through the managed floors or a framework BOM moving with the \
                target. Name packages, not counts.
                STUCK:       which will still be there afterwards whatever the hop does, and why: \
                no fixed version published, a committed jar rather than a resolved dependency, or a \
                transitive pinned by something that is not moving.
                FAMILIES:    any multi-artifact family here that must move in lockstep (jackson-*, \
                netty-*, logback-*, spring-*). A family split across versions is a broken build, and \
                naming it is worth more than any count.

                You are reading, not prescribing. Do not propose edits: nothing in this chain lifts \
                a dependency for security, and an edit made for it costs reward and risks the tests.
                """);
    }

    /** Judges the reading against the same scan: overclaiming is the failure mode. */
    Agent securityBeforeCritic() {
        return runtime("security-before-critic", read("security-before-critic"), """
                A colleague read a pre-migration vulnerability scan and said which findings a Java \
                LTS bump could plausibly clear. Judge the READING, not the project.

                The expensive mistake is OVERCLAIMING: calling a package reachable when nothing in a \
                version bump moves it. A committed jar in the tree, a dependency with no fixed \
                version published, and a transitive pinned by a framework outside this hop are all \
                stuck, whatever the package name suggests.

                The other mistake is a MISSED FAMILY: a multi-artifact family named only in part, \
                which is how a build breaks while its version numbers all look raised.

                Answer `sound`, or `overclaimed: <package and why it will not move>`, or \
                `missed-family: <family>`, one finding per line.
                """);
    }

    // ---- pair 2: the proactive steps ----

    /**
     * Executes the proactive steps: the structure-gated moves that must land BEFORE the first target
     * build, where the deterministic pre-pass could not reach (Gradle DSL edits, module-local pins,
     * judgement about which trigger actually fired).
     */
    Agent preparer() {
        return runtime("preparer", patch("preparer"), """
                You prepare a Java project for a one-LTS migration BEFORE its first target build. \
                Every step is gated on a structural trigger; check each trigger against the project \
                and land the step ONLY where it fires. A deterministic pre-pass has already run: what \
                it did travels in the brief, do not redo it.

                The steps, by trigger (versions are measured floors, not folklore):
                - the project resolves Lombok, declared anywhere or transitively: floor it to 1.18.30, \
                or 1.18.46 when the target is 25 plus the maven.compiler.proc=full property, since \
                JDK 23+ no longer runs classpath annotation processors by default. When a Spring BOM \
                arrives at scope=import, a property override is a silent no-op: use a \
                dependencyManagement entry in the root pom instead.
                - Gradle, wrapper below the target's floor (7.6 for 17, 8.10.2 for 21, 9.1.0 for 25): \
                set distributionUrl in gradle/wrapper/gradle-wrapper.properties.
                - the project declares JaCoCo: floor it to 0.8.15, in the module that declares it.
                - the project mocks (mockito, byte-buddy, MockK): force byte-buddy 1.14.12, or 1.17.6 \
                when the target is 25, and mockito-core 5.18.0.
                - a Kotlin build with target 25: kotlin 2.3.20 in every pom that pins it; every 1.x \
                either crashes or silently falls back below the target.
                - a test dependency that reflects into the process environment (junit-pioneer, \
                system-lambda, system-rules): add --add-opens java.base/java.util=ALL-UNNAMED and \
                java.base/java.lang=ALL-UNNAMED to the test fork, at the root so modules inherit it.

                Use edit_file to land each step. Then STOP and answer in one line: \
                DID: <steps executed, and which triggers did not fire>. If every trigger is already \
                satisfied, answer exactly NOTHING-TO-DO: <why>. Do not keep exploring once the work \
                is done; that is what exhausts a tool budget.
                """);
    }

    /** Judges the preparation against the same trigger list the preparer carries. */
    Agent prepareCritic() {
        return runtime("prepare-critic", read("prepare-critic"), """
                A colleague prepared a Java project for a one-LTS migration. The steps are gated on \
                structural triggers: Lombok resolved (floor 1.18.30, or 1.18.46 and proc=full at \
                target 25), Gradle wrapper below the target's floor (7.6/8.10.2/9.1.0), JaCoCo \
                declared (0.8.15), mocking present (byte-buddy 1.14.12 or 1.17.6, mockito 5.18.0), \
                Kotlin at target 25 (2.3.20), env-mutating test libs (--add-opens). Read the project \
                and judge TWO things, nothing else.

                MISSED: a trigger fires here and no edit answers it. Name the step and the file that \
                proves the trigger fired.

                OVERREACH: an edit answers no trigger, or changes something the steps never asked \
                for. Structure-gated means gated: a step applied "just in case" is how a working \
                build gets broken by its own preparation.

                Answer `sound`, or `missed: <step>` or `overreach: <what>`, one finding per line, \
                most damaging first.
                """);
    }

    // ---- pair 3: land the target ----

    /** Lands the effective bytecode target: the pins the recipe under-applied, in every dialect. */
    Agent bumper() {
        return runtime("bumper", patch("bumper"), """
                A migration recipe has run and a deterministic sweep has raised what it could. Your \
                job is what is LEFT: every version pin, toolchain block, property or compiler flag \
                still below the target in ANY module, in whichever dialect this build speaks \
                (maven.compiler.source/target/release, java.version, sourceCompatibility, \
                kotlin jvmTarget, JavaLanguageVersion.of, a bare <release> inside a plugin).

                The gate measures the MINIMUM class-file major across every compiled main class \
                (55 for 11, 61 for 17, 65 for 21, 69 for 25), so ONE unraised module fails the whole \
                bump while the build stays green. A green build is not the goal; the landed target is.

                The pins found still below target travel in the brief. Raise each with edit_file, \
                then STOP and answer one line: DID: <what you raised>. If the list is genuinely empty \
                and your own grep agrees, answer exactly NOTHING-TO-DO: <why>.
                """);
    }

    /** Checks the landing: the pin grep is re-run for it, so it judges the state, not the claim. */
    Agent bumpCritic() {
        return runtime("bump-critic", read("bump-critic"), """
                A colleague raised a project's remaining Java target pins. Judge ONE question: after \
                these edits, does the effective bytecode target actually reach the target in EVERY \
                module the build compiles?

                The pins still below target after the edits travel in the brief, and you can grep for \
                more. The expensive mistakes: a module-local property that shadows the fixed parent, \
                a second pin in the same file (a toolchain block AND an options.release), and an edit \
                that raises a pin the build never reads.

                Answer `sound`, or `not-landed: <file and pin still below target>`, or \
                `overreach: <edit that changes something other than a target pin>`.
                """);
    }

    // ---- pair 4: the residue the wall table does not know ----

    /** Clears the wall no signature matched: the residue a model is for. */
    /**
     * Drives the campaign: decides the NEXT step, or that there is no next step.
     *
     * <p>The step agent below fixes one thing and knows nothing of what came before it. Something
     * has to hold the sequence, notice that three attempts have circled the same wall, and choose
     * between pressing on and going back. That is a judgement, so it belongs to an agent rather than
     * to a loop counter, and it is why this one can see the landed steps and rewind past a line that
     * led nowhere.
     */
    Agent troubleshootLoopProposer(Tree tree, String floor) {
        return runtime("troubleshoot-loop", steer("troubleshoot-loop", tree, floor), """
                You are running the troubleshooting for one JDK migration. The gate has failed and                 the deterministic wall table recognised nothing in the failure.

                You do not edit anything yourself. You decide what the next step should be, one                 step at a time, and a colleague carries it out and is reviewed for it. Your job is                 the sequence: what to try, in what order, and when to stop.

                Before choosing, look at steps_so_far. If earlier steps circled the same wall                 without moving it, do not order a fourth variation on them. Consider whether the                 line of attack was wrong from the start, and if it was, rewind_to the step it began                 from and say plainly what you are abandoning and why.

                Answer exactly one of:
                NEXT: <one concrete step, the wall it clears, and where to look>
                DONE: <what was cleared, and why the gate should now pass>
                BLOCKED: <the wall, what makes it impassable, and the evidence you checked>

                BLOCKED is a real answer and sometimes the right one. It earns nothing when it                 stands in for not having looked: a dependency is only impassable once inspect_jar                 has shown you which of its classes are the problem and what else it carries.
                """);
    }

    /**
     * Judges the campaign, not the step, and can put the workspace back if it was the wrong campaign.
     *
     * <p>The step critic reviews one edit at a time and cannot see a run of individually reasonable
     * steps adding up to nothing, or a declaration of defeat that had a route left. This one reads
     * the whole thing and is the only agent that may send the loop back to where it started.
     */
    Agent troubleshootLoopCritic(Tree tree, String floor) {
        return runtime("troubleshoot-loop-critic", steer("troubleshoot-loop-critic", tree, floor), """
                A colleague ran the troubleshooting for a JDK migration and has stopped. You decide                 whether the job is actually done.

                You are reviewing the CAMPAIGN, not any single edit: a reviewer has already passed                 each step. Read what the sequence adds up to. Use steps_so_far and inspect_jar to                 check the claims rather than take them.

                Two failures to look for. A run of individually sensible steps that never reached                 the wall, each one reasonable and the whole going nowhere. And a BLOCKED that gave                 up early: an artifact called impossible when inspect_jar shows only one or two of                 its classes are the obstacle and the rest is usable, or when the declared                 dependencies underneath it were never looked at.

                Answer `done` if the campaign is finished, right or genuinely blocked.

                Otherwise answer `again: <what was missed, and where to start>`. Say it as a                 colleague would: name the specific thing not tried and the evidence for why it                 would work. If the work so far is in the way of that, rewind_to a step first and                 say so. An objection without a route is the same as `done`.
                """);
    }

    Agent troubleshooter() {
        return runtime("troubleshooter", patch("troubleshooter"), """
                You are the reflect loop's residue handler: the deterministic wall table recognised \
                nothing in this failure. Known wall families, for orientation only — the table has \
                already tried their exact signatures, so the failure is a variant or something new: \
                APIs removed from the JDK, strong encapsulation, bytecode-reading tools too old for \
                the new class-file major, annotation processors silently disabled, JUnit 4 to 5 \
                fallout stripping transitive test dependencies.

                Diagnose the FIRST real error in the log, not the last line. Read the files it names. \
                Then make the SMALLEST edit that clears it, and check it with try_build before you \
                answer.

                When a Spring context fails to start, the cause is the line that names the bean it \
                could not create. Everything after it, including page after page of \
                "ApplicationContext failure threshold exceeded", is that one failure repeating.

                inspect_jar reads a dependency's own class files, which the project's sources cannot \
                tell you. Use it before you conclude anything about a dependency. It answers whether \
                a type is a class or an interface, whether the artifact is compiled against javax or \
                jakarta, and how it registers with Spring. A Boot 2 era artifact that declares \
                itself only in META-INF/spring.factories contributes NO beans under Boot 3, because \
                Boot 3 reads META-INF/spring/...AutoConfiguration.imports instead, and a missing \
                bean fails the whole context and every test in the module with it.

                An abandoned artifact is not always a dead end. Where a jar is javax-compiled with no \
                jakarta release, inspect_jar will usually show only one or two classes needing javax \
                while its interfaces, its @ConfigurationProperties types, its exception hierarchy and \
                its factory methods need nothing of the sort. Keeping the artifact and supplying \
                jakarta versions of just the blocking classes preserves the configuration and the \
                behaviour; replacing the artifact wholesale rarely does.

                Answer one line: WHY: <the wall, and why this clears it>. If the wall cannot be \
                cleared without editing a test, answer exactly BLOCKED: <why>. Before answering \
                BLOCKED because a dependency has no compatible version, say what inspect_jar showed: \
                which classes are the blocker and why they cannot be worked around. That is a useful \
                answer; a speculative edit is not.
                """);
    }

    /** Judges the troubleshooting edit: migration fix, or gaming the gate? */
    Agent troubleCritic() {
        return runtime("trouble-critic", read("trouble-critic"), """
                A colleague edited a project to get it past its migration build gate. Judge ONE \
                question: is this a migration fix, or does it game the gate? Read the diff and the \
                files around it.

                Gaming looks like: weakening or skipping tests by configuration (surefire excludes, \
                skipTests, a disabled failsafe), deleting functionality instead of migrating it, \
                pinning a tool to a version that ignores the new bytecode rather than reading it, or \
                editing generated output instead of its source.

                Answer `sound` when it is a real migration step a maintainer would keep. Answer \
                `gaming` and name the exact line when it is not. Answer `off-target` when the edit is \
                honest but aims at the wrong wall: say which wall the log actually shows.
                """);
    }

    // ---- pair 6: what the bump actually did to the vulnerabilities ----

    /**
     * Judges the accounting the chain computed, rather than producing one.
     *
     * <p>Cleared, remaining and introduced are a set difference over (module, package, CVE) and are
     * computed exactly in {@link Security#compare}. Handing that to a model to recompute would turn
     * an exact answer into a sampled one, which is the thing this chain refuses to do everywhere
     * else. What is left for a reader is the question arithmetic cannot settle: whether the delta
     * is a migration outcome or an artefact.
     */
    Agent securityAfter() {
        return runtime("security-after", read("security-after"), """
                You are handed a before and after vulnerability scan of a Java project that has just \
                been migrated one LTS step, and the exact accounting the harness computed between \
                them: how many findings cleared, how many remain, how many are new.

                The arithmetic is settled. Answer the question it cannot:

                ATTRIBUTION: is this delta a migration outcome? A count that fell because the \
                framework BOM moved with the target is a real outcome. A count that fell because a \
                dependency dropped out of the graph is not the same thing, and neither is one that \
                fell because a module stopped resolving.
                REGRESSION:  if anything is NEW, name it and say what pulled it in. A bump that \
                clears forty findings and introduces one critical is not obviously a win.
                RESIDUE:     what remains, in one line: the family or the single package that now \
                dominates the count.

                Start with one word: `improved`, `regressed`, or `artefact` when you judge the \
                numbers do not describe a real change. Then the three points.
                """);
    }

    /** Checks the judgement against the numbers, since the numbers are the one thing not in doubt. */
    Agent securityAfterCritic() {
        return runtime("security-after-critic", read("security-after-critic"), """
                A colleague judged whether a migration's vulnerability delta is a real outcome. You \
                are given the same two scans and the same computed accounting. Judge the JUDGEMENT.

                The failure mode is CREDIT FOR A COLLAPSE: reading a count that fell because the \
                project resolved less as though libraries had been upgraded. The harness flags that \
                case, so check whether the colleague respected the flag.

                The opposite failure is DISMISSING A REAL WIN: calling a genuine framework-driven \
                clearance an artefact because it looks too large.

                Answer `sound`, or `wrong-call: <what the numbers actually show>`.
                """);
    }

    // ---- the closers ----

    /** Argues only what execution could not settle. */
    Agent verdict() {
        return runtime("verdict", read("verdict"), """
                A Java version bump ended without the gate establishing a verdict. You argue what \
                this bump IS, from the record you are given and whatever you read to check it.

                `blocked-dependency`  — a dependency has no version compatible with the target JDK. \
                Name it and say what was tried.
                `behavior-change`     — the target JDK changed observable behaviour and only a test \
                edit could reconcile it, which the rules forbid. Name the exact change.
                `infra`               — the environment failed the bump: resolution, timeouts, disk. \
                A tooling failure must not read as a migration failure.

                One word first, then the argument. These mean different things to whoever reads this \
                next, so choose the word for the reader.
                """);
    }

    /** Prices the attempt from the record. */
    Agent estimator() {
        return runtime("estimator", read("estimator"), """
                You read a completed attempt to bump a Java project one LTS step, and estimate what \
                the same work would have cost a competent Java developer who had not seen this code \
                before.

                Charge the work actually done, not the outcome: reading the failing build, each wall \
                recognised and cleared, each edit made and reviewed, and the dead ends — a human \
                would have paid for those attempts too. A wall the table cleared in one turn still \
                cost a person the diagnosis.

                Answer with ONE line first: `minutes: N`. Then three to six lines itemising what you \
                charged, saying which part dominated.
                """);
    }

    // ---- wiring ----

    private Map<ToolSpecification, ToolExecutor> read(String agent) {
        return Tools.reading(ws, trace, agent);
    }

    /** Read, look inside jars, and move between landed steps. The outer troubleshoot pair. */
    private Map<ToolSpecification, ToolExecutor> steer(String agent, Tree tree, String floor) {
        return Tools.steering(ws, tree, floor, trace, agent);
    }

    private Map<ToolSpecification, ToolExecutor> patch(String agent) {
        return Tools.patching(ws, runner, targetJdk, trace, agent);
    }

    /** One agent, already wired to the trace. Callers cannot reach a runtime that is not. */
    /** The same agent, asked again without thinking. Built on demand: most calls never need it. */
    private Agent retry(String name, Map<ToolSpecification, ToolExecutor> tools, String prompt) {
        SubAgentRuntime r = new SubAgentRuntime(Model.forRetry(trace), prompt, tools,
                "agent:" + name + ":retry", ToolInvocationLogMode.NONE,
                trace instanceof JsonlTrace j ? j : null);
        return r::run;
    }

    private Agent runtime(String name, Map<ToolSpecification, ToolExecutor> tools, String prompt) {
        // A critic judges; a producer works. They get different models because they fail
        // differently, and a critic that spends its budget thinking answers nothing at all.
        boolean judges = name.endsWith("-critic") || name.equals("verdict")
                || name.equals("estimator");
        SubAgentRuntime runtime = new SubAgentRuntime(judges ? judging : model, prompt, tools,
                "agent:" + name, ToolInvocationLogMode.NONE,
                trace instanceof JsonlTrace j ? j : null);
        return task -> {
            String reply;
            try {
                // An agent that answers with tool calls and no content returns null. That is an
                // empty judgement, not a failure, and everything downstream reads it as one.
                reply = runtime.run(task);
            } catch (RuntimeException e) {
                // An unreachable model withholds. A critic that withholds waives; a producer that
                // withholds has done nothing, and the gate will say so next turn.
                reply = "";
                trace.progress("", name + " unreachable: " + e.getMessage());
            }
            reply = reply == null ? "" : reply;
            if (reply.isBlank()) {
                // AN EMPTY ANSWER IS NOT A JUDGEMENT, and must not be read as one: every critic's
                // word list defaults to its approving word, so silence would approve. Ask once
                // more, plainly, and record both attempts.
                trace.asked(name, prompt + "\n\n---\n\n" + task, "");
                trace.progress("", name + " answered nothing; asking once more without thinking");
                try {
                    // A blank means the reasoning entered a cycle greedy decoding cannot leave, so
                    // the retry asks a model that does not reason at all. Instructing the thinking
                    // model to be brief instead was measured to make the second attempt WORSE than
                    // the first: 80% runaway against a 62.5% control.
                    reply = retry(name, tools, prompt).run(task);
                } catch (RuntimeException e) {
                    reply = "";
                }
                reply = reply == null ? "" : reply;
            }
            trace.asked(name, prompt + "\n\n---\n\n" + task, reply);
            return reply;
        };
    }
}
