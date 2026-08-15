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
 * EVERY AGENT IN THE CHAIN, each with its own closed set of answers and its own closed set of tools.
 *
 * <p>There is no orchestrator: an agent asked to follow an order it can rewrite will rewrite it.
 * {@link Bump} runs the order and every doer's work is judged before the chain moves, because the
 * expensive mistake at each phase is different and a single reviewer prompted for everything reviews
 * nothing well.
 *
 * <p>THE UNIT IS A TRIAD, NOT A PAIR. A planner decides, a doer executes one plan once, and a
 * verifier reads the workspace and returns done, again or replan. The loop belongs to the verifier:
 * when the producer held it, the critic saw only the end state, and a pin check that answered about
 * an arbitrary module went unnoticed for as long as that shape existed. See {@link Triad}.
 *
 * <p>PLANNERS AND VERIFIERS READ, DOERS EDIT, AND THE SPLIT DECIDES THE TOOLS. A doer reaches the
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

                CALL build_system FIRST. apply_recipe runs the OpenRewrite MAVEN plugin, so on a
                Gradle module no recipe can execute and this phase has no other way to write. That
                is not a recipe that failed and it is not worth a retry: say which modules are
                Gradle and that the pins for them are unapplied, and let the bump phase, which does
                hold an editor, deal with them. A phase that reports "every pin met" because its
                only tool could not start is the worst answer available here.

                USE apply_recipe. Do not edit a pom or a build.gradle by hand. A version can live in
                a dependency, in dependencyManagement, in a property the dependency reads, in a
                Gradle string or in a version catalog, and hand-editing means guessing which -- the
                recipes know, for both build systems. Emit the maven form AND the gradle form for
                every pin in one recipe file; the one that does not match this project matches
                nothing, which is what lets one recipe serve either.

                Then call declared_versions and read what the project actually says now. It reports
                what each module declares and where; comparing that to the floors above is your job.
                If something you meant to raise still reads low, look at why -- usually the recipe
                did not match where that version lives -- and run another. What the files say is the
                fact; your recollection of what you ran is not.

                YOU ARE WORKING TO A PLAN SOMEONE ELSE SETTLED, and it names the modules. Raise what
                the plan names and leave the rest: a sibling that declares the same artifact lower is
                a different piece of work, and doing it here makes this stage impossible to judge.

                {ALSO}

                Answer one line: DONE: <what you raised, and what was already satisfied>. If a pin
                cannot be met, say exactly BLOCKED: <which, and what the project does that prevents
                it>. Both are useful answers. A claim that everything is done when declared_versions says
                otherwise is the one answer that is not.
                """;

    private static final String P_PINS_PLANNER = """
                A Java project is being moved from JDK {FROM} to JDK {TARGET}, and you decide what
                the next pass of pin work should be. {WHEN}

{PINS}

                Each line is group:artifact, the version it must be at least, and why. They are
                floors: at or above one is finished, and a project that does not use a dependency at
                all is not given it.

                Call declared_versions. It reports what each module declares and WHERE -- a parent
                block, a dependency, a plugin, a property, a Gradle string, the wrapper -- and it
                does not judge any of it. Deciding which of those sit below the floors above is your
                job, and it is the whole job.

                READ WHAT THE DECLARATION MEANS, not just its name. A Maven project says which Spring
                Boot it is on by inheriting spring-boot-starter-parent or importing
                spring-boot-dependencies, never by declaring an artifact called spring-boot. A
                starter with no version is managed by that parent and moves when it moves. A version
                that reads ${something} is an indirection: the property is what has to change. A tool
                that matched artifact names literally missed all three, which is why this is a
                planner's question and not a regex's.

                You do not edit anything. Produce a short ordered list: each pin that is below its
                floor, the module it is below it in, and where in that module the version lives.
                Where it lives decides which recipe can move it, and a plan that skips it hands the
                next agent a guess.

                If nothing is below its floor, say exactly NOTHING-OUTSTANDING and stop. That is a
                real answer and it is common: most modules inherit their versions, and a project that
                does not use a dependency is not given it.
                """;

    private static final String P_PINS_CRITIC = """
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

                You hold the loop. Answer with one of three words.

                `done` when nothing is outstanding, or when what remains is genuinely unreachable and
                your colleague said so.

                UNREACHABLE IS A REAL ANSWER AND YOU CAN CHECK IT. apply_recipe runs the OpenRewrite
                MAVEN plugin and this phase holds no other way to write, so on a Gradle module no
                pin here can be applied by anyone, however many times you ask. Call build_system.
                If it says the module is Gradle and your colleague said so too, that is `done`: name
                the pin and say it is unapplied and why, so the bump phase, which does hold an
                editor, picks it up. Answering `again` there sends someone back to a tool that
                cannot start, and it happened twice in one phase before this paragraph existed.

                `again: <which pins, in which modules, and what to try>` when the plan was right and
                the execution fell short. Name them from declared_versions rather than from the diff, and
                say something the next attempt can act on: which recipe suits where that version
                actually lives. An objection without that is the same as `done`.

                `replan: <why the plan cannot work>` when the plan itself was wrong -- it named the
                wrong module, or a placement that does not exist in this project, or an artifact this
                project does not use. Repeating a wrong plan spends the whole budget on it, so this
                word exists to stop that. Use it for the plan, not for a bad attempt at a good plan.
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
    private static final String P_BUMPER = """
                You move a Java project from JDK {FROM} to JDK {TARGET}. The dependencies the new
                JDK needs are already in place; this is the step that actually changes the target.

                START WITH THE RECIPES. apply_recipe runs them, and they do the structural work no
                hand edit should attempt -- the module changes, the plugin floors, the compatibility
                settings, on either build system:

{RECIPES}

                CALL build_system FIRST. It reports, per module, whether it is Maven, Gradle or
                both. apply_recipe runs the OpenRewrite MAVEN plugin, so on a Gradle module it
                cannot execute any recipe at all -- not this one, not another, not on a retry.
                Measured: roughly a third of this corpus is Gradle, and those bumps reached the gate
                having changed nothing while the agent called apply_recipe again. Ask before you
                call, rather than reading the failure afterwards.

                On a Gradle module edit_file is the whole toolkit, and it is enough for a version.
                Read the build files and raise what the pins and the target need, wherever the
                project keeps it:

                - plugins { id 'org.springframework.boot' version 'X' }, or the Kotlin DSL
                  id("org.springframework.boot") version "X"
                - the older buildscript form,
                  classpath 'org.springframework.boot:spring-boot-gradle-plugin:X'
                - a property the dependencies read: ext['x.version'] in Groovy,
                  extra["x.version"] in Kotlin, or a [versions] entry in gradle/libs.versions.toml
                - the version inside a dependency string, implementation 'group:artifact:version'
                - distributionUrl in gradle/wrapper/gradle-wrapper.properties for the wrapper floor

                A pin you cannot reach any of those ways is worth saying so about. A pin you did not
                try because the recipe failed is not.

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
    private static final String P_BUMP_PLANNER = """
                A Java project is being moved to JDK {TARGET}, and you decide what the next pass of
                target-raising should be. You do not edit anything.

                Call check_target. It reports every source, target, release, sourceCompatibility,
                jvmTarget and toolchain declaration still below {TARGET}, with module, file and line.

                This is the pin the GATE measures, and it measures the LOWEST module. A plan that
                fixes the root and leaves a child behind produces a green-looking build and a
                FAIL_target_not_bumped four stages later, so group what you find by module and say
                which are genuinely separate declarations rather than one property read from several
                places.

                Produce a short ordered list: module, file and line, and whether it should move by
                recipe or by hand. A module-local property that shadows a fixed parent is the mistake
                that costs most here; if you see one, say so plainly.

                If check_target reports nothing below {TARGET}, say exactly NOTHING-OUTSTANDING.
                """;

    private static final String P_BUMP_CRITIC = """
                A colleague raised a project's remaining Java target pins. Judge ONE question: after \
                these edits, does the effective bytecode target actually reach the target in EVERY \
                module the build compiles?

                Call check_target rather than reading the diff. It answers per module, and the gate \
                takes the LOWEST, so a module left behind fails the whole bump however good the root \
                looks. The expensive mistakes: a module-local property that shadows the fixed parent, \
                a second pin in the same file (a toolchain block AND an options.release), and an edit \
                that raises a pin the build never reads.

                You hold the loop. Answer with one of three words.

                `done` when nothing check_target reports sits below the target, or when what remains \
                genuinely cannot move and your colleague said which and why.

                `again: <module, file and pin still below target>` when the plan was right and the \
                execution fell short.

                `replan: <why the plan cannot work>` when the plan named the wrong place -- a pin the \
                build never reads, a file that does not exist, a property shadowed elsewhere. \
                Repeating a wrong plan spends the whole budget on it.

                There is a fourth thing worth saying inside any of those: if an edit changes something \
                other than a target pin, name it. Overreach here is how a bump acquires a behaviour \
                change nobody asked for.
                """;

    private static final String P_MODULE_FILTER = """
                A Java project is being moved from JDK {FROM} to JDK {TARGET}. The harness has read
                the build files and enumerated the modules; that list is a fact and you are not
                asked to correct it.

                You are asked a narrower thing: is any module on it something this bump should leave
                alone? The two that matter are a VENDORED third-party source tree checked into the
                repository, and a module that is GENERATED by the build rather than written. Editing
                either produces a diff the project's own tooling will overwrite or reject, and this
                corpus has spent whole bumps doing exactly that.

                Read before you answer. A directory named `third-party` that turns out to be the
                project's own code is not vendored, and a module named `generated` that is checked in
                and hand-edited is not generated. Evidence is a header, a LICENSE that is not the
                project's, a .gitignore that covers it, or a plugin that writes it.

                Answer either exactly KEEP-ALL, or one line per module to skip:
                SKIP <module path>: <the evidence>.

                KEEP-ALL is the common and correct answer. A module skipped is a module that keeps
                its old target, and the gate takes the lowest, so a wrong skip fails the whole bump.
                """;

    private static final String P_MODULE_FILTER_CRITIC = """
                A colleague was asked which modules of this project the bump should leave alone, and
                answered. Judge whether the answer is safe.

                The asymmetry is the whole job. Keeping a module that should have been skipped costs
                a wasted diff. Skipping a module that should have been kept leaves it on the old
                target, and the gate takes the LOWEST module, so it fails the entire bump and the
                evidence points at the wrong place.

                So a skip needs evidence you can see, and you have the tools to look. Read the module.
                If the reason is a directory name, that is not evidence.

                Answer `done` when the list is safe, `again: <which skip is unevidenced>` when a skip
                should be dropped or one is missing, or `replan: <why>` if the answer did not address
                the question asked.
                """;

    private static final String P_MODULE_PLANNER = """
                A Java project is being moved from JDK {FROM} to JDK {TARGET}, one module at a time,
                and you are looking at ONE module. Its path is in the brief.

                Say what this module needs, and nothing about its siblings. Call declared_versions and
                check_target, both of which answer per module, and read only the rows for yours.

                Most modules need very little. A Maven child usually inherits its versions and its
                compiler settings from the parent, in which case the honest plan is that the parent
                carries them and this module has nothing of its own. Say that. A plan that invents
                work for a module which inherits everything produces edits that shadow the parent,
                which is the single most expensive mistake available here: a module-local property
                that overrides a correctly-raised parent leaves the gate reading the old target.

                Answer either exactly INHERITS: <what it takes from the parent>, or a short ordered
                list of what this module itself declares and must move.
                """;

    private static final String P_MODULE_VERIFIER = """
                One module of a Java project has been through its pin and target work. Judge whether
                it is finished, for that module alone.

                Call declared_versions and check_target and read the rows for this module. Two questions:
                is every floor met here, and is every target declaration here at {TARGET} or above.
                A module that declares neither is finished, because it inherits both.

                You hold the loop. `done` when the module is clear or genuinely cannot be moved and
                your colleague said why. `again: <what is still outstanding here>` when the work fell
                short. `replan: <why>` when what was attempted does not fit this module -- most often
                because the declaration being chased lives in the parent, not here.

                Do not send back for something that belongs to a sibling. Each module is judged on its
                own, and the repository's verdict is the product of theirs.
                """;

    // ---- the planners of the stages that used to be pairs ----------------------------------
    //
    // A pair collapses deciding into doing, and the agent that chose an approach is then the one
    // asked whether the approach was right. Each of these decides what the stage is actually for
    // and holds no tool that writes; the verifier can then say `replan` and mean something.

    private static final String P_SURVEY_PLANNER = """
                A Java project has been QUEUED for a bump from JDK {FROM} to JDK {TARGET}. Before
                anyone reads it, say what would settle whether the project agrees it is at {FROM}.

                The hop is prescribed and nothing may change it. This is not a decision about where
                the project should go; it is a decision about which evidence answers where it IS.

                Name the files worth reading and what in them would count. The honest answer is
                usually short: the compiler pins in the root build file, a toolchain block, a CI
                workflow that names a JDK, a wrapper properties file. Say which of those exist here.

                A project can also declare several levels at once. If it does, say which one the
                build actually compiles with, because that is the one the question is about.
                """;

    private static final String P_SECURITY_PLANNER = """
                A vulnerability scan of a Java project has been taken, and a colleague will read it.
                Decide what the reading should cover.

                You do not edit anything and nothing downstream lifts a dependency for security. What
                this produces is a record, so the useful plan is about RELEVANCE: of the families in
                this scan, which could a JDK {FROM} to {TARGET} bump plausibly move, and which are
                untouched by it?

                A bump moves versions, so a finding in a dependency the floors already raise is
                reachable, and a finding in something the bump never touches is not. Saying which is
                which is the whole job; a reading that credits a bump with clearing a CVE it never
                came near is the failure this exists to prevent.

                Answer as a short list of families, each marked reachable or untouched.
                """;

    private static final String P_MODULE_FILTER_PLANNER = """
                A Java project being moved from JDK {FROM} to JDK {TARGET} has more than one module,
                and a colleague will decide whether any should be left alone. Say where to look.

                Only two things justify skipping a module: it is a VENDORED third-party source tree
                checked into the repository, or it is GENERATED by the build rather than written.
                Both leave evidence, and the evidence is what you are planning to find: a licence
                that is not the project's, a file header naming another author, a .gitignore that
                covers the directory, a plugin configured to write it.

                Name the modules whose paths or contents suggest either, and what to read to confirm
                it. If nothing does, say exactly NOTHING-SUSPECT: that is the common answer.

                Keep the asymmetry in view. A module wrongly kept costs a wasted diff. A module
                wrongly skipped keeps its old target, and the gate takes the lowest module, so it
                fails the entire bump.
                """;

    private static final String P_TROUBLESHOOT_PLANNER = """
                A Java project being moved to JDK {TARGET} has failed its gate, and a campaign of
                fixes is about to start. Decide what the campaign is FOR before anyone edits.

                Read the first real error in the log, not the last line. Then say which of these the
                failure is, because they call for different campaigns: an API removed from the JDK,
                strong encapsulation refusing access, a bytecode-reading tool too old for the new
                class-file major, an annotation processor silently disabled, JUnit 4 to 5 fallout, or
                something outside all of them.

                Say what a finished campaign would look like -- which error should be gone, and what
                would show it. A campaign with no stated end runs until its budget is spent.

                If the failure is not this bump's doing, say exactly NOT-OURS and why. A test that
                was red before anything moved is not a wall, and treating it as one has cost this
                corpus whole runs.
                """;

    private static final String P_VERDICT_PLANNER = """
                A bump from JDK {FROM} to {TARGET} ended without a green gate, and a colleague will
                argue it into one word. Decide what is actually unsettled.

                Most of the run is already decided by the builds and needs no argument. Your job is
                to name the ONE question the execution could not answer, and what evidence in the
                record bears on it.

                Be specific about what would distinguish the candidates. A dependency with no release
                for this JDK and a dependency nobody tried to raise both look like blocked-dependency
                in a log, and the difference is whether an attempt appears in the record.

                Answer in two or three lines: the question, and where the answer would be found.
                """;

    private static final String P_ESTIMATOR_PLANNER = """
                A bump from JDK {FROM} to {TARGET} has finished, and a colleague will price the work
                a developer would have done. Decide what there is to price.

                Read the record and list the distinct pieces of work that actually landed: a pin
                raised, a wall cleared, a test repaired, a module moved. What did not land is not
                work, and neither is an edit that was reverted.

                Name each piece once. The expensive error here is double counting: a fix attempted
                three times and landed once is one piece of work, and the record shows all three.
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
    Agent surveyDoer() {
        return agent("survey-doer");
    }

    /** Checks the reading against the same tree. Objects only with a correction in hand. */
    Agent surveyVerifier() {
        return agent("survey-verifier");
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
    Agent securityBeforeDoer() {
        return agent("security-before-doer");
    }

    /** Judges the reading against the same scan: overclaiming is the failure mode. */
    Agent securityBeforeVerifier() {
        return agent("security-before-verifier");
    }

    // ---- pair 2: the proactive steps ----


    // ---- pair 3: land the target ----

    /** Lands the effective bytecode target: the pins the recipe under-applied, in every dialect. */
    Agent bumpDoer() {
        return agent("bump-doer");
    }

    /** Checks the landing: the pin grep is re-run for it, so it judges the state, not the claim. */
    Agent bumpVerifier() {
        return agent("bump-verifier");
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
    Agent stepPlanner(String floor) {
        return runtime("step-planner", steer("step-planner", floor),
                P_TROUBLESHOOT_LOOP);
    }

    /**
     * Judges the campaign, not the step, and can put the workspace back if it was the wrong campaign.
     *
     * <p>The step critic reviews one edit at a time and cannot see a run of individually reasonable
     * steps adding up to nothing, or a declaration of defeat that had a route left. This one reads
     * the whole thing and is the only agent that may send the loop back to where it started.
     */
    Agent troubleshootVerifier(String floor) {
        return runtime("troubleshoot-verifier", steer("troubleshoot-verifier", floor),
                P_TROUBLESHOOT_LOOP_CRITIC);
    }

    Agent stepDoer() {
        return agent("step-doer");
    }

    /** Judges the troubleshooting edit: migration fix, or gaming the gate? */
    Agent stepVerifier() {
        return agent("step-verifier");
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
    Agent securityAfterDoer() {
        return agent("security-after-doer");
    }

    /** Checks the judgement against the numbers, since the numbers are the one thing not in doubt. */
    Agent securityAfterVerifier() {
        return agent("security-after-verifier");
    }

    // ---- the closers ----

    /** Argues only what execution could not settle. */
    Agent verdictDoer() {
        return agent("verdict-doer");
    }

    /** Prices the attempt from the record. */
    Agent verdictVerifier() {
        return agent("verdict-verifier");
    }

    Agent estimatorVerifier() {
        return agent("estimator-verifier");
    }

    Agent estimatorDoer() {
        return agent("estimator-doer");
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

    Agent beforePinsDoer() {
        return runtime("before-pins-doer", Tools.pinning(ws, recipes(), tree, String.valueOf(hop.from()), trace, "before-pins-doer"), pinPrompt(P_PINS, false));
    }

    Agent beforePinsVerifier() {
        return runtime("before-pins-verifier",
                Tools.judging(ws, tree, trace, "before-pins-verifier"),
                pinPrompt(P_PINS_CRITIC, false));
    }

    Agent afterPinsDoer() {
        return runtime("after-pins-doer", Tools.pinning(ws, recipes(), tree, String.valueOf(hop.to()), trace, "after-pins-doer"), pinPrompt(P_PINS, true));
    }

    Agent afterPinsVerifier() {
        return runtime("after-pins-verifier",
                Tools.judging(ws, tree, trace, "after-pins-verifier"),
                pinPrompt(P_PINS_CRITIC, true));
    }

    /**
     * The planners: they read and decide, and they hold no tool that writes.
     *
     * <p>Separating them from the doers is what lets a verifier say `replan` and mean something. A
     * producer that both chose the approach and carried it out has no way to be told the approach
     * was wrong, only that the attempt was, so an objection to the plan came back as another attempt
     * at the same plan until the budget ran out.
     */
    Agent beforePinsPlanner() {
        return runtime("before-pins-planner",
                Tools.judging(ws, tree, trace, "before-pins-planner"),
                pinPrompt(P_PINS_PLANNER, false));
    }

    Agent afterPinsPlanner() {
        return runtime("after-pins-planner",
                Tools.judging(ws, tree, trace, "after-pins-planner"),
                pinPrompt(P_PINS_PLANNER, true));
    }

    Agent bumpPlanner() {
        return runtime("bump-planner",
                Tools.checking(ws, tree, String.valueOf(hop.to()), trace, "bump-planner"),
                floors(P_BUMP_PLANNER));
    }

    /** Which modules this bump should leave alone, and a reviewer of that answer. */
    Agent moduleFilterDoer() {
        return runtime("module-filter-doer", read("module-filter-doer"), floors(P_MODULE_FILTER));
    }

    Agent moduleFilterVerifier() {
        return runtime("module-filter-verifier", read("module-filter-verifier"),
                floors(P_MODULE_FILTER_CRITIC));
    }

    /** One module's own plan, and the verifier that closes it. Both read per module. */
    Agent modulesPlanner() {
        return runtime("modules-planner",
                Tools.judging(ws, tree, trace, "modules-planner"),
                floors(P_MODULE_PLANNER));
    }

    Agent modulesVerifier() {
        return runtime("modules-verifier",
                Tools.judging(ws, tree, trace, "modules-verifier"),
                floors(P_MODULE_VERIFIER));
    }

    /** The planners of the stages that used to be pairs. None of them holds a tool that writes. */
    Agent surveyPlanner() {
        return runtime("survey-planner", read("survey-planner"), floors(P_SURVEY_PLANNER));
    }

    Agent securityBeforePlanner() {
        return runtime("security-before-planner", read("security-before-planner"),
                floors(P_SECURITY_PLANNER));
    }

    Agent securityAfterPlanner() {
        return runtime("security-after-planner", read("security-after-planner"),
                floors(P_SECURITY_PLANNER));
    }

    Agent moduleFilterPlanner() {
        return runtime("module-filter-planner", read("module-filter-planner"),
                floors(P_MODULE_FILTER_PLANNER));
    }

    Agent troubleshootPlanner() {
        return runtime("troubleshoot-planner", steer("troubleshoot-planner", ""),
                floors(P_TROUBLESHOOT_PLANNER));
    }

    Agent verdictPlanner() {
        return runtime("verdict-planner", read("verdict-planner"), floors(P_VERDICT_PLANNER));
    }

    Agent estimatorPlanner() {
        return runtime("estimator-planner", read("estimator-planner"), floors(P_ESTIMATOR_PLANNER));
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
                define("survey-planner", "says what would settle which JDK this project is on",
                        floors(P_SURVEY_PLANNER), read("survey-planner")),
                define("survey-doer", "reads what JDK the project is actually on", P_SURVEYOR,
                        read("survey-doer")),
                define("survey-verifier", "checks the survey against the build files", P_SURVEY_CRITIC,
                        read("survey-verifier")),
                define("security-before-planner", "says which CVE families this hop could move",
                        floors(P_SECURITY_PLANNER), read("security-before-planner")),
                define("security-before-doer", "reads the pre-bump vulnerability scan",
                        P_SECURITY_BEFORE, read("security-before-doer")),
                define("security-before-verifier", "checks that reading", P_SECURITY_BEFORE_CRITIC,
                        read("security-before-verifier")),
                define("module-filter-planner", "says where to look for a vendored or generated module",
                        floors(P_MODULE_FILTER_PLANNER), read("module-filter-planner")),
                define("module-filter-doer", "says which modules this bump should leave alone",
                        floors(P_MODULE_FILTER), read("module-filter-doer")),
                define("module-filter-verifier", "checks every skip is evidenced",
                        floors(P_MODULE_FILTER_CRITIC), read("module-filter-verifier")),
                define("modules-planner", "says what one module needs, and nothing about its siblings",
                        floors(P_MODULE_PLANNER), read("modules-planner")),
                define("before-pins-planner", "decides which pins to raise, in which module",
                        pinPrompt(P_PINS_PLANNER, false), read("before-pins-planner")),
                define("before-pins-doer", "raises the versions the new JDK needs, before it moves",
                        pinPrompt(P_PINS, false),
                        Tools.pinning(ws, recipes(), tree, String.valueOf(hop.from()), trace, "before-pins-doer")),
                define("before-pins-verifier", "checks every pre-JDK pin landed, module by module",
                        pinPrompt(P_PINS_CRITIC, false), read("before-pins-verifier")),

                define("bump-planner", "groups the remaining target declarations by module",
                        floors(P_BUMP_PLANNER),
                        Tools.checking(ws, tree, targetJdk, trace, "bump-planner")),
                define("bump-doer", "moves the project to the target JDK", bumpPrompt(),
                        Tools.raising(ws, runner, recipes(), tree, targetJdk, String.valueOf(hop.to()), trace, "bump-doer")),
                define("bump-verifier", "checks every module reached the target", P_BUMP_CRITIC,
                        Tools.checking(ws, tree, targetJdk, trace, "bump-verifier")),
                define("troubleshoot-planner", "says what the campaign is for, and when it is over",
                        floors(P_TROUBLESHOOT_PLANNER), steer("troubleshoot-planner", "")),
                define("troubleshoot-verifier", "judges the campaign, not the step",
                        P_TROUBLESHOOT_LOOP_CRITIC, steer("troubleshoot-verifier", "")),
                define("step-planner", "decides the next step, and when to stop",
                        P_TROUBLESHOOT_LOOP, steer("step-planner", "")),
                define("step-doer", "clears one wall the deterministic table did not know",
                        P_TROUBLESHOOTER, patch("step-doer")),
                define("step-verifier", "migration fix, or gaming the gate", P_TROUBLE_CRITIC,
                        read("step-verifier")),
                define("after-pins-planner", "decides which post-JDK pins to raise, in which module",
                        pinPrompt(P_PINS_PLANNER, true), read("after-pins-planner")),
                define("after-pins-doer", "raises the versions that only run on the new JDK",
                        pinPrompt(P_PINS, true),
                        Tools.pinning(ws, recipes(), tree, String.valueOf(hop.to()), trace, "after-pins-doer")),
                define("after-pins-verifier", "checks every post-JDK pin landed, module by module",
                        pinPrompt(P_PINS_CRITIC, true), read("after-pins-verifier")),
                define("modules-verifier", "closes one module, or sends it back",
                        floors(P_MODULE_VERIFIER), read("modules-verifier")),
                define("security-after-planner", "says which findings the bump could have moved",
                        floors(P_SECURITY_PLANNER), read("security-after-planner")),
                define("security-after-doer", "reads what the bump did to the vulnerability count",
                        P_SECURITY_AFTER, read("security-after-doer")),
                define("security-after-verifier", "checks that judgement", P_SECURITY_AFTER_CRITIC,
                        read("security-after-verifier")),
                define("verdict-planner", "names the one question execution could not settle",
                        floors(P_VERDICT_PLANNER), read("verdict-planner")),
                define("verdict-doer", "argues an unsettled bump into the corpus vocabulary", P_VERDICT,
                        read("verdict-doer")),
                define("verdict-verifier", "checks that word against what actually happened",
                        P_VERDICT_CRITIC, read("verdict-verifier")),
                define("estimator-planner", "lists the distinct pieces of work that landed",
                        floors(P_ESTIMATOR_PLANNER), read("estimator-planner")),
                define("estimator-doer", "prices the work a developer would have done", P_ESTIMATOR,
                        read("estimator-doer")),
                define("estimator-verifier", "checks the price against the work in the log",
                        P_ESTIMATOR_CRITIC, read("estimator-verifier")));
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

    /**
     * THE LIVE AGENT, AND THE ONE PLACE AN EDIT TAKES EFFECT.
     *
     * <p>{@link #define} deliberately does NOT consult the store: it builds the catalogue the
     * settings page reads, and that page has to be able to show the code's own text beside the edit
     * in order to offer a revert at all. So the built-in travels there and the override lands here,
     * where an agent is actually about to be asked something.
     *
     * <p>Read at construction, which is once per bump. A bump that changed its own instructions
     * halfway through would be a bump nobody could reproduce.
     */
    private Agent runtime(String name, Map<ToolSpecification, ToolExecutor> tools, String built) {
        String edited = Prompts.override(name, hop);
        if (!edited.isBlank()) {
            trace.progress("", "prompt: " + name + " is an edit, not the code's own");
        }
        // Effectively final, because the lambda below closes over it.
        final String prompt = edited.isBlank() ? built : edited;
        // A critic judges; a producer works. They get different models because they fail
        // differently, and a critic that spends its budget thinking answers nothing at all.
        boolean judges = name.endsWith("-critic") || name.equals("verdict-doer")
                || name.equals("estimator-doer");
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
