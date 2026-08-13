package tech.mikhailov.bjv.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.deepagents.langchain4j.logging.ToolInvocationLogMode;
import com.deepagents.langchain4j.subagents.SubAgentDefinition;
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
    private final Hop hop;
    private final Tree tree;
    private Migrate migrate;

    Agents(ChatModel model, Path ws, Runner runner, Tree tree, Hop hop, Trace trace) {
        this(model, Model.forCritic(trace), ws, runner, tree, hop, trace);
    }

    Agents(ChatModel model, ChatModel judging, Path ws, Runner runner, Tree tree,
           Hop hop, Trace trace) {
        this.judging = judging;
        this.model = model;
        this.ws = ws;
        this.runner = runner;
        this.tree = tree;
        this.hop = hop;
        this.targetJdk = String.valueOf(hop.to());
        this.trace = trace;
    }



    /**
     * The floor versions the prompts quote, resolved from the one table that also applies them.
     *
     * <p>Two prompts typed these numbers out. Raising a floor while the instructions still named
     * the old one would tell an agent to do one thing while the code did another, silently.
     */
    /**
     * The floor rules this hop can actually reach, written into the prompt that applies them.
     *
     * <p>The instructions used to name every rule for every target: an 8-to-11 preparer was told
     * about Kotlin 2.3.20 for JDK 25 and the jakarta move at 21, neither of which it can reach. A
     * rule that cannot fire is not just wasted context, it is an invitation to apply it anyway.
     */
    private String floors(String prompt) {
        return prompt.replace("{FLOORS}", hop.floorsAsInstructions())
                .replace("{TARGET}", String.valueOf(hop.to()))
                .replace("{FROM}", String.valueOf(hop.from()));
    }

    private static final String P_PINS = """
                You raise dependency versions on a Java project that is being moved from JDK {FROM}
                to JDK {TARGET}. {WHEN}

                THESE, AND NOTHING ELSE:

{PINS}

                Each line is group:artifact, the version it must be at least, and why. They are
                floors: a project already at or above one is finished, and a project that does not
                use a dependency at all is not given it. Never lower a version.

                USE apply_recipe. Do not edit a pom or a build.gradle by hand. A version can live in
                a dependency, in dependencyManagement, in a property the dependency reads, in a
                Gradle string or in a version catalog, and hand-editing means guessing which -- the
                recipes know, for both build systems. Emit the maven form AND the gradle form for
                every pin in one recipe file; the one that does not match this project matches
                nothing, which is what lets one recipe serve either.

                Then call check_pins and read what the project actually says. If something is still
                outstanding, look at why -- the wrong recipe for where that version lives, or an
                artifact this project genuinely does not use -- and run another recipe. The check is
                the fact; your recollection of what you ran is not.

                {ALSO}

                Answer one line: DONE: <what you raised, and what was already satisfied>. If a pin
                cannot be met, say exactly BLOCKED: <which, and what the project does that prevents
                it>. Both are useful answers. A claim that everything is done when check_pins says
                otherwise is the one answer that is not.
                """;

    private static final String P_PINS_CRITIC = """
                A colleague was asked to raise these versions on a project moving from JDK {FROM} to
                JDK {TARGET}. {WHEN}

{PINS}

                Call check_pins and read what the build files say. That is the whole question: is
                every pin at or above its floor, or is one outstanding.

                A dependency the project does not use is satisfied -- these are floors, not
                requirements, and adding one would be a different bump. A version above the floor is
                satisfied. Only a version BELOW the floor is outstanding.

                Answer `done` when nothing is outstanding, or when what remains is genuinely
                unreachable and your colleague said so.

                Otherwise answer `again: <which pins, and what to try>`. Name them from check_pins
                rather than from the diff, and say something the next attempt can act on: which
                recipe suits where that version actually lives in this project. An objection without
                that is the same as `done`.
                """;

    private static final String P_SURVEYOR = """
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
                """;
    private static final String P_SURVEY_CRITIC = """
                A colleague read which Java level a project is really on. The hop itself is \
                prescribed elsewhere and is not in question. Judge the READING.

                The expensive mistakes: taking a parent's property when the modules override it, \
                taking the newest pin in a tree whose oldest module decides the level, and reading \
                a compiler `release` flag as the level the build itself requires.

                Answer `sound`, or `reads-as: <version>` with the file and line that proves it. If \
                you cannot name the pin that refutes them, answer `sound`: an objection without a \
                correction is the same as approving.
                """;
    private static final String P_SECURITY_BEFORE = """
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
                """;
    private static final String P_SECURITY_BEFORE_CRITIC = """
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
                """;
    private static final String P_PREPARER = """
                You prepare a Java project for a one-LTS migration BEFORE its first target build. \
                Every step is gated on a structural trigger; check each trigger against the project \
                and land the step ONLY where it fires. A deterministic pre-pass has already run: what \
                it did travels in the brief, do not redo it.

                This hop is JDK {FROM} to {TARGET}. The floors that can fire here, and nothing \
                else, because a rule you cannot reach is one you should not apply:

{FLOORS}

                Placement matters as much as the version. When a Spring BOM arrives at scope=import, \
                a property override is a silent no-op: use a dependencyManagement entry in the root \
                pom. A Gradle build has no dependencyManagement at all, so its floors go in the \
                wrapper properties and a resolutionStrategy, and they are yours to apply because \
                the deterministic pass is Maven-only.

                Two triggers that are not version floors: a Kotlin build at target 25 needs kotlin \
                pinned in every pom that names it, and a test dependency that reflects into the \
                process environment (junit-pioneer, system-lambda, system-rules) needs \
                --add-opens java.base/java.util=ALL-UNNAMED and java.base/java.lang=ALL-UNNAMED on \
                the test fork, at the root so modules inherit it.

                Use edit_file to land each step. Then STOP and answer in one line: \
                DID: <steps executed, and which triggers did not fire>. If every trigger is already \
                satisfied, answer exactly NOTHING-TO-DO: <why>. Do not keep exploring once the work \
                is done; that is what exhausts a tool budget.
                """;
    private static final String P_PREPARE_CRITIC = """
                A colleague prepared a Java project for JDK {FROM} to {TARGET}. The steps are \
                gated on structural triggers, and these are the floors that can fire on this hop:

{FLOORS}

                Read the project and judge TWO things, nothing else.

                MISSED: a trigger fires here and no edit answers it. Name the step and the file that \
                proves the trigger fired.

                OVERREACH: an edit answers no trigger, or changes something the steps never asked \
                for. Structure-gated means gated: a step applied "just in case" is how a working \
                build gets broken by its own preparation.

                Answer `sound`, or `missed: <step>` or `overreach: <what>`, one finding per line, \
                most damaging first.
                """;
    private static final String P_BUMPER = """
                You move a Java project from JDK {FROM} to JDK {TARGET}. The dependencies the new
                JDK needs are already in place; this is the step that actually changes the target.

                START WITH THE RECIPES. apply_recipe runs them, and they do the structural work no
                hand edit should attempt -- the module changes, the plugin floors, the compatibility
                settings, on either build system:

{RECIPES}

                THEN FINISH WHAT THEY MISSED. check_target reads every build file and reports the
                source, target, release, sourceCompatibility, jvmTarget and toolchain declarations
                still below {TARGET}, with file and line. Recipes do not reach every dialect a
                project can use, so read that list and raise what is left with edit_file.

                Then call check_target AGAIN. It is the same measurement the gate makes, so a
                declaration you leave behind is a bump that fails four stages later with nothing
                left to try. Do not answer while it still reports something below {TARGET} unless
                you can say why that one cannot move.

                Raise, never lower, and change nothing that is not a version or a compatibility
                setting. Answer one line: DID: <what the recipes moved, what you raised by hand, and
                what check_target says now>.
                """;
    private static final String P_BUMP_CRITIC = """
                A colleague raised a project's remaining Java target pins. Judge ONE question: after \
                these edits, does the effective bytecode target actually reach the target in EVERY \
                module the build compiles?

                The pins still below target after the edits travel in the brief, and you can grep for \
                more. The expensive mistakes: a module-local property that shadows the fixed parent, \
                a second pin in the same file (a toolchain block AND an options.release), and an edit \
                that raises a pin the build never reads.

                Answer `sound`, or `not-landed: <file and pin still below target>`, or \
                `overreach: <edit that changes something other than a target pin>`.
                """;
    private static final String P_TROUBLESHOOTER = """
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

                gradle_versions lists the Gradle distributions a build here can actually use. Read it \
                before touching distributionUrl: the builds are sealed, Gradle's version numbers are \
                not contiguous, and a wrapper raised to a version that was never published spends \
                the whole patience budget trying to download it. One troubleshooter went 8.15 to \
                8.16; neither exists.

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
                """;
    private static final String P_TROUBLE_CRITIC = """
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
                """;
    private static final String P_SECURITY_AFTER = """
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
                """;
    private static final String P_SECURITY_AFTER_CRITIC = """
                A colleague judged whether a migration's vulnerability delta is a real outcome. You \
                are given the same two scans and the same computed accounting. Judge the JUDGEMENT.

                The failure mode is CREDIT FOR A COLLAPSE: reading a count that fell because the \
                project resolved less as though libraries had been upgraded. The harness flags that \
                case, so check whether the colleague respected the flag.

                The opposite failure is DISMISSING A REAL WIN: calling a genuine framework-driven \
                clearance an artefact because it looks too large.

                Answer `sound`, or `wrong-call: <what the numbers actually show>`.
                """;
    private static final String P_VERDICT = """
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
                """;
    private static final String P_VERDICT_CRITIC = """
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
                """;

    private static final String P_ESTIMATOR_CRITIC = """
                A colleague has priced what this bump would have cost a competent Java developer who
                had not seen the code before. You check the number.

                It is not a scoring input and nothing downstream depends on it, which is exactly why
                it drifts: an unchecked number gets read later as though it were measured.

                Read the run with what_happened and ask three things. Does the total match the work
                the log shows -- the walls actually hit, the edits actually made, the attempts that
                failed and were retried? Is anything charged that did not happen, or charged twice
                because the trace records several attempts at the same bump? And is anything real
                left out: a wall the deterministic table cleared in one turn still cost a person the
                diagnosis, and a dead end still cost the time it took to abandon.

                Answer `sound`, or `off: <the number it should be, and which items are wrong>`.
                Being roughly right matters more than being precise -- an estimate within a
                reasonable band is sound, and only a total that the log does not support is off.
                """;

    private static final String P_ESTIMATOR = """
                You read a completed attempt to bump a Java project one LTS step, and estimate what \
                the same work would have cost a competent Java developer who had not seen this code \
                before.

                Charge the work actually done, not the outcome: reading the failing build, each wall \
                recognised and cleared, each edit made and reviewed, and the dead ends — a human \
                would have paid for those attempts too. A wall the table cleared in one turn still \
                cost a person the diagnosis.

                Answer with ONE line first: `minutes: N`. Then three to six lines itemising what you \
                charged, saying which part dominated.
                """;

    private static final String P_TROUBLESHOOT_LOOP = """
                You are running the troubleshooting for one JDK migration. The gate has failed and                 the deterministic wall table recognised nothing in the failure.

                You do not edit anything yourself. You decide what the next step should be, one                 step at a time, and a colleague carries it out and is reviewed for it. Your job is                 the sequence: what to try, in what order, and when to stop.

                Before choosing, look at steps_so_far. If earlier steps circled the same wall                 without moving it, do not order a fourth variation on them. Consider whether the                 line of attack was wrong from the start, and if it was, rewind_to the step it began                 from and say plainly what you are abandoning and why.

                Answer exactly one of:
                NEXT: <one concrete step, the wall it clears, and where to look>
                DONE: <what was cleared, and why the gate should now pass>
                BLOCKED: <the wall, what makes it impassable, and the evidence you checked>

                BLOCKED is a real answer and sometimes the right one. It earns nothing when it                 stands in for not having looked: a dependency is only impassable once inspect_jar                 has shown you which of its classes are the problem and what else it carries.
                """;
    private static final String P_TROUBLESHOOT_LOOP_CRITIC = """
                A colleague ran the troubleshooting for a JDK migration and has stopped. You decide                 whether the job is actually done.

                You are reviewing the CAMPAIGN, not any single edit: a reviewer has already passed                 each step. Read what the sequence adds up to. Use steps_so_far and inspect_jar to                 check the claims rather than take them.

                Two failures to look for. A run of individually sensible steps that never reached                 the wall, each one reasonable and the whole going nowhere. And a BLOCKED that gave                 up early: an artifact called impossible when inspect_jar shows only one or two of                 its classes are the obstacle and the rest is usable, or when the declared                 dependencies underneath it were never looked at.

                Answer `done` if the campaign is finished, right or genuinely blocked.

                Otherwise answer `again: <what was missed, and where to start>`. Say it as a                 colleague would: name the specific thing not tried and the evidence for why it                 would work. If the work so far is in the way of that, rewind_to a step first and                 say so. An objection without a route is the same as `done`.
                """;
    // ---- pair 1: which hop is this, actually ----

    /** Reads the build files and names the hop. The deterministic detector's guess travels along. */
    Agent surveyor() {
        return agent("surveyor");
    }

    /** Checks the reading against the same tree. Objects only with a correction in hand. */
    Agent surveyCritic() {
        return agent("survey-critic");
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
        return agent("security-before");
    }

    /** Judges the reading against the same scan: overclaiming is the failure mode. */
    Agent securityBeforeCritic() {
        return agent("security-before-critic");
    }

    // ---- pair 2: the proactive steps ----

    /**
     * Executes the proactive steps: the structure-gated moves that must land BEFORE the first target
     * build, where the deterministic pre-pass could not reach (Gradle DSL edits, module-local pins,
     * judgement about which trigger actually fired).
     */
    Agent preparer() {
        return agent("preparer");
    }

    /** Judges the preparation against the same trigger list the preparer carries. */
    Agent prepareCritic() {
        return agent("prepare-critic");
    }

    // ---- pair 3: land the target ----

    /** Lands the effective bytecode target: the pins the recipe under-applied, in every dialect. */
    Agent bumper() {
        return agent("bumper");
    }

    /** Checks the landing: the pin grep is re-run for it, so it judges the state, not the claim. */
    Agent bumpCritic() {
        return agent("bump-critic");
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
    Agent troubleshootLoopProposer(String floor) {
        return runtime("troubleshoot-loop", steer("troubleshoot-loop", floor),
                P_TROUBLESHOOT_LOOP);
    }

    /**
     * Judges the campaign, not the step, and can put the workspace back if it was the wrong campaign.
     *
     * <p>The step critic reviews one edit at a time and cannot see a run of individually reasonable
     * steps adding up to nothing, or a declaration of defeat that had a route left. This one reads
     * the whole thing and is the only agent that may send the loop back to where it started.
     */
    Agent troubleshootLoopCritic(String floor) {
        return runtime("troubleshoot-loop-critic", steer("troubleshoot-loop-critic", floor),
                P_TROUBLESHOOT_LOOP_CRITIC);
    }

    Agent troubleshooter() {
        return agent("troubleshooter");
    }

    /** Judges the troubleshooting edit: migration fix, or gaming the gate? */
    Agent troubleCritic() {
        return agent("trouble-critic");
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
        return agent("security-after");
    }

    /** Checks the judgement against the numbers, since the numbers are the one thing not in doubt. */
    Agent securityAfterCritic() {
        return agent("security-after-critic");
    }

    // ---- the closers ----

    /** Argues only what execution could not settle. */
    Agent verdict() {
        return agent("verdict");
    }

    /** Prices the attempt from the record. */
    Agent verdictCritic() {
        return agent("verdict-critic");
    }

    Agent estimatorCritic() {
        return agent("estimator-critic");
    }

    Agent estimator() {
        return agent("estimator");
    }

    // ---- wiring ----

    private Map<ToolSpecification, ToolExecutor> read(String agent) {
        return Tools.reading(ws, tree, trace, agent);
    }

    /** Read, look inside jars, and move between landed steps. The outer troubleshoot pair. */
    private Map<ToolSpecification, ToolExecutor> steer(String agent, String floor) {
        return Tools.steering(ws, tree, floor, trace, agent);
    }

    private Map<ToolSpecification, ToolExecutor> patch(String agent) {
        return Tools.patching(ws, runner, tree, targetJdk, trace, agent);
    }

    /** One agent, already wired to the trace. Callers cannot reach a runtime that is not. */
    /** The same agent, asked again without thinking. Built on demand: most calls never need it. */
    private Agent retry(String name, Map<ToolSpecification, ToolExecutor> tools, String prompt) {
        SubAgentRuntime r = new SubAgentRuntime(Model.forRetry(trace), prompt, tools,
                "agent:" + name + ":retry", ToolInvocationLogMode.NONE,
                trace instanceof JsonlTrace j ? j : null);
        return r::run;
    }

    /**
     * EVERY AGENT, AS DATA, BUILT FOR THIS HOP.
     *
     * <p>{@link SubAgentDefinition} is the framework's own shape and this class used to hand-roll a
     * worse one: sixteen factory methods each assembled a name, a prompt and a tool set and then
     * threw the assembly away, which is why the prompts could only be read by opening this file and
     * why there was no way to ask what a different hop would be told.
     *
     * <p>As data they can be listed, rendered, diffed between hops, and composed. The order is the
     * order the chain reaches them.
     */
    /** The pins this hop owes before the JDK moves, and the ones that must wait until after. */
    List<Floors.Floor> owed(boolean afterTheJdkMoves) {
        String text = afterTheJdkMoves ? Floors.after(hop.to()) : Floors.before(hop.to());
        return Floors.at(hop.to()).stream()
                .filter(f -> text.contains(f.coordinates() + " " + f.version()))
                .toList();
    }

    /** One of the two pin phases, as a pair. The list in the prompt is the list the tool checks. */
    /** The steps in a phase that are not version pins, and only the before phase has any. */
    private String also(boolean after) {
        if (after) {
            return "";
        }
        return """
                TWO THINGS THAT ARE NOT VERSION PINS, and only apply if the project shows the
                trigger. Use apply_recipe for these too --
                org.openrewrite.maven.ChangePropertyValue reaches a property in every pom.

                - a test dependency that reflects into the process environment (junit-pioneer,
                  system-lambda, system-rules): the test fork needs
                  --add-opens java.base/java.util=ALL-UNNAMED and java.base/java.lang=ALL-UNNAMED,
                  set at the root so modules inherit it.
                - target 23 or above with annotation processors on the classpath: set
                  maven.compiler.proc=full. JDK 23 stopped running them by default, so a floored
                  Lombok that is never invoked is the same as no Lombok at all.
                """;
    }

    /**
     * The bumper's instructions, carrying the recipe program for THIS hop.
     *
     * <p>These recipes used to run in a deterministic pass of their own, which chose them by a
     * lookup and then inspected the project to pick the Spring one -- judgement, in a step drawn as
     * code, and wrong on six of 102 workspaces until it was fixed. The recipes are named here and
     * the agent runs them, checks what moved, and runs more if the target is still not met.
     */
    private String bumpPrompt() {
        StringBuilder recipes = new StringBuilder();
        recipes.append("                - org.openrewrite.java.migrate.UpgradePluginsForJava")
                .append(hop.to()).append('\n')
                .append("                - org.openrewrite.java.migrate.UpgradeBuildToJava")
                .append(hop.to()).append('\n')
                .append("                - org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo\n");
        if (hop.crosses(11)) {
            recipes.append("                - org.openrewrite.java.migrate.Java8toJava11 "
                    + "— the only recipe that handles the modules JEP 320 removed, and this hop "
                    + "crosses rung 11\n");
        }
        recipes.append("                - org.openrewrite.gradle.UpdateJavaCompatibility with "
                + "version ").append(hop.to()).append(" — the Gradle half, a no-op on Maven\n");
        return P_BUMPER.replace("{RECIPES}", recipes.toString())
                .replace("{FROM}", String.valueOf(hop.from()))
                .replace("{TARGET}", String.valueOf(hop.to()));
    }

    private String pinPrompt(String base, boolean after) {
        return base.replace("{ALSO}", also(after)).replace("{PINS}", (after ? Floors.after(hop.to()) : Floors.before(hop.to()))
                        .lines().map(l -> "                - " + l.strip())
                        .collect(java.util.stream.Collectors.joining("\n")))
                .replace("{FROM}", String.valueOf(hop.from()))
                .replace("{TARGET}", String.valueOf(hop.to()))
                .replace("{WHEN}", after
                        ? "The JDK has already been raised. These versions require it, so this is "
                        + "the first moment they can be applied at all."
                        : "The JDK has NOT been raised yet. These versions must be in place first, "
                        + "because the new JDK will not compile without them.");
    }

    Agent beforePins() {
        return runtime("before-pins", Tools.pinning(ws, recipes(), tree, String.valueOf(hop.from()),
                owed(false), trace, "before-pins"), pinPrompt(P_PINS, false));
    }

    Agent beforePinsCritic() {
        return runtime("before-pins-critic",
                Tools.judging(ws, tree, owed(false), trace, "before-pins-critic"),
                pinPrompt(P_PINS_CRITIC, false));
    }

    Agent afterPins() {
        return runtime("after-pins", Tools.pinning(ws, recipes(), tree, String.valueOf(hop.to()),
                owed(true), trace, "after-pins"), pinPrompt(P_PINS, true));
    }

    Agent afterPinsCritic() {
        return runtime("after-pins-critic",
                Tools.judging(ws, tree, owed(true), trace, "after-pins-critic"),
                pinPrompt(P_PINS_CRITIC, true));
    }

    /** The recipe runner, set once the workspace is known. Agents that pin cannot work without it. */
    Agents withRecipes(Migrate migrate) {
        this.migrate = migrate;
        return this;
    }

    private Migrate recipes() {
        return migrate != null ? migrate : new Migrate(ws, "", trace);
    }

    List<SubAgentDefinition> definitions() {
        return List.of(
                define("surveyor", "reads what JDK the project is actually on", P_SURVEYOR,
                        read("surveyor")),
                define("survey-critic", "checks the survey against the build files", P_SURVEY_CRITIC,
                        read("survey-critic")),
                define("security-before", "reads the pre-bump vulnerability scan",
                        P_SECURITY_BEFORE, read("security-before")),
                define("security-before-critic", "checks that reading", P_SECURITY_BEFORE_CRITIC,
                        read("security-before-critic")),
                define("before-pins", "raises the versions the new JDK needs, before it moves",
                        pinPrompt(P_PINS, false),
                        Tools.pinning(ws, recipes(), tree, String.valueOf(hop.from()),
                                owed(false), trace, "before-pins")),
                define("before-pins-critic", "checks every pre-JDK pin actually landed",
                        pinPrompt(P_PINS_CRITIC, false), read("before-pins-critic")),

                define("bumper", "moves the project to the target JDK", bumpPrompt(),
                        Tools.raising(ws, runner, recipes(), tree, targetJdk, String.valueOf(hop.to()), trace, "bumper")),
                define("bump-critic", "checks every pin reached the target", P_BUMP_CRITIC,
                        Tools.checking(ws, tree, targetJdk, trace, "bump-critic")),
                define("troubleshoot-loop", "decides the next step, and when to stop",
                        P_TROUBLESHOOT_LOOP, steer("troubleshoot-loop", "")),
                define("troubleshoot-loop-critic", "judges the campaign, not the step",
                        P_TROUBLESHOOT_LOOP_CRITIC, steer("troubleshoot-loop-critic", "")),
                define("troubleshooter", "clears one wall the deterministic table did not know",
                        P_TROUBLESHOOTER, patch("troubleshooter")),
                define("trouble-critic", "migration fix, or gaming the gate", P_TROUBLE_CRITIC,
                        read("trouble-critic")),
                define("after-pins", "raises the versions that only run on the new JDK",
                        pinPrompt(P_PINS, true),
                        Tools.pinning(ws, recipes(), tree, String.valueOf(hop.to()),
                                owed(true), trace, "after-pins")),
                define("after-pins-critic", "checks every post-JDK pin actually landed",
                        pinPrompt(P_PINS_CRITIC, true), read("after-pins-critic")),
                define("security-after", "reads what the bump did to the vulnerability count",
                        P_SECURITY_AFTER, read("security-after")),
                define("security-after-critic", "checks that judgement", P_SECURITY_AFTER_CRITIC,
                        read("security-after-critic")),
                define("verdict", "argues an unsettled bump into the corpus vocabulary", P_VERDICT,
                        read("verdict")),
                define("verdict-critic", "checks that word against what actually happened",
                        P_VERDICT_CRITIC, read("verdict-critic")),
                define("estimator", "prices the work a developer would have done", P_ESTIMATOR,
                        read("estimator")),
                define("estimator-critic", "checks the price against the work in the log",
                        P_ESTIMATOR_CRITIC, read("estimator-critic")));
    }

    /**
     * The definitions for a hop, without a model or a workspace: for reading rather than running.
     *
     * <p>The settings page asks what a 17-to-21 bump will be told before one is running, which is
     * the whole point of being able to see them.
     */
    static List<SubAgentDefinition> forHop(Hop hop, Path ws) {
        return new Agents(null, null, ws, null, new Tree(ws, note -> { }), hop, null).definitions();
    }

    /** One definition. The framework's record, so nothing here reinvents its shape. */
    private SubAgentDefinition define(String name, String description, String prompt,
                                      Map<ToolSpecification, ToolExecutor> tools) {
        return new SubAgentDefinition(name, description, prompt, false, tools);
    }

    /** The definition by name, which is how the chain asks for one. */
    SubAgentDefinition definition(String name) {
        return definitions().stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no agent called " + name));
    }

    /** A runnable agent from its definition. */
    private Agent agent(String name) {
        SubAgentDefinition d = definition(name);
        return runtime(d.name(), d.extraTools(), d.systemPrompt());
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

    /** One agent's instructions, with where it sits and what it is for. */
    record Prompt(String name, String role, String text) { }

    /**
     * EVERY PROMPT, IN THE ORDER THE CHAIN REACHES THEM.
     *
     * <p>These are the whole behaviour of the system that is not code: what each agent is asked, and
     * therefore what it does. They were readable only by opening this file, which meant the one part
     * anybody would want to inspect was the one part nobody could see while a sweep was running.
     */
    static List<Prompt> prompts() {
        return List.of(
                new Prompt("surveyor", "producer", P_SURVEYOR),
                new Prompt("survey-critic", "critic", P_SURVEY_CRITIC),
                new Prompt("security-before", "producer", P_SECURITY_BEFORE),
                new Prompt("security-before-critic", "critic", P_SECURITY_BEFORE_CRITIC),
                new Prompt("preparer", "producer", P_PREPARER),
                new Prompt("prepare-critic", "critic", P_PREPARE_CRITIC),
                new Prompt("bumper", "producer", P_BUMPER),
                new Prompt("bump-critic", "critic", P_BUMP_CRITIC),
                new Prompt("troubleshoot-loop", "producer", P_TROUBLESHOOT_LOOP),
                new Prompt("troubleshoot-loop-critic", "critic", P_TROUBLESHOOT_LOOP_CRITIC),
                new Prompt("troubleshooter", "producer", P_TROUBLESHOOTER),
                new Prompt("trouble-critic", "critic", P_TROUBLE_CRITIC),
                new Prompt("security-after", "producer", P_SECURITY_AFTER),
                new Prompt("security-after-critic", "critic", P_SECURITY_AFTER_CRITIC),
                new Prompt("verdict", "closer", P_VERDICT),
                new Prompt("estimator", "closer", P_ESTIMATOR));
    }
}
