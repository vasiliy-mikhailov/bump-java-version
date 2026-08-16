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

    /**
     * A PROMPT, READ FROM A FILE RATHER THAN COMPILED IN.
     *
     * <p>Six hundred and thirty-six lines of this file were prompt text: fifty-one per cent of it,
     * against three hundred and eighty lines of actual code. It is prose aimed at a model, versioned
     * as Java, and the cost of that is paid on every edit. A comma in one of these needed a Maven
     * build, a jar copy, a docker build and a deploy before anyone could see whether it read better.
     *
     * <p>The route already existed twice over: {@link Bom} loads its lists from
     * {@code /bom/<key>.tsv} the same way, and {@link Prompts} already reads an EDITED prompt off
     * disk and hands it to the settings page. This is the built-in half arriving by the same door.
     *
     * <p>ONE FILE PER DISTINCT TEXT, not per agent. before-pins-doer and after-pins-doer are handed
     * the same prompt with different substitution, and a file each would be the same words twice,
     * which is the duplication this move is supposed to reduce rather than create.
     *
     * <p>A missing or empty file throws HERE, at class initialisation, rather than handing an agent
     * nothing to do halfway through a bump. An agent given an empty prompt does something arbitrary
     * and the trace records it as a decision.
     */
    private static String text(String key) {
        try (var in = Agents.class.getResourceAsStream("/prompts/" + key + ".md")) {
            if (in == null) {
                throw new IllegalStateException("no prompt at /prompts/" + key + ".md");
            }
            String body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.isBlank()) {
                throw new IllegalStateException("the prompt at /prompts/" + key + ".md is empty");
            }
            return body;
        } catch (java.io.IOException unreadable) {
            throw new IllegalStateException("could not read /prompts/" + key + ".md", unreadable);
        }
    }

    private static final String P_PINS = text("pins");

    private static final String P_PINS_PLANNER = text("pins-planner");

    private static final String P_PINS_CRITIC = text("pins-critic");

    private static final String P_SURVEYOR = text("surveyor");
    private static final String P_SURVEY_CRITIC = text("survey-critic");
    private static final String P_SECURITY_BEFORE = text("security-before");
    private static final String P_SECURITY_BEFORE_CRITIC = text("security-before-critic");
    private static final String P_BUMPER = text("bumper");
    private static final String P_BUMP_PLANNER = text("bump-planner");

    private static final String P_BUMP_CRITIC = text("bump-critic");

    private static final String P_MODULE_FILTER = text("module-filter");

    private static final String P_MODULE_FILTER_CRITIC = text("module-filter-critic");

    private static final String P_MODULE_PLANNER = text("module-planner");

    private static final String P_MODULE_VERIFIER = text("module-verifier");

    // ---- the planners of the stages that used to be pairs ----------------------------------
    //
    // A pair collapses deciding into doing, and the agent that chose an approach is then the one
    // asked whether the approach was right. Each of these decides what the stage is actually for
    // and holds no tool that writes; the verifier can then say `replan` and mean something.

    private static final String P_SURVEY_PLANNER = text("survey-planner");

    private static final String P_SECURITY_PLANNER = text("security-planner");

    private static final String P_MODULE_FILTER_PLANNER = text("module-filter-planner");

    private static final String P_TROUBLESHOOT_PLANNER = text("troubleshoot-planner");

    private static final String P_VERDICT_PLANNER = text("verdict-planner");

    private static final String P_ESTIMATOR_PLANNER = text("estimator-planner");

    private static final String P_TROUBLESHOOTER = text("troubleshooter");
    private static final String P_TROUBLE_CRITIC = text("trouble-critic");
    private static final String P_SECURITY_AFTER = text("security-after");
    private static final String P_SECURITY_AFTER_CRITIC = text("security-after-critic");
    private static final String P_VERDICT = text("verdict");
    private static final String P_VERDICT_CRITIC = text("verdict-critic");

    private static final String P_ESTIMATOR_CRITIC = text("estimator-critic");

    private static final String P_ESTIMATOR = text("estimator");

    private static final String P_TROUBLESHOOT_LOOP = text("troubleshoot-loop");
    private static final String P_TROUBLESHOOT_LOOP_CRITIC = text("troubleshoot-loop-critic");
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
    Agent moduleRepairStepPlanner(String floor) {
        return runtime("module-repair-step-planner", steer("module-repair-step-planner", floor),
                P_TROUBLESHOOT_LOOP);
    }

    /**
     * Judges the campaign, not the step, and can put the workspace back if it was the wrong campaign.
     *
     * <p>The step critic reviews one edit at a time and cannot see a run of individually reasonable
     * steps adding up to nothing, or a declaration of defeat that had a route left. This one reads
     * the whole thing and is the only agent that may send the loop back to where it started.
     */
    Agent moduleRepairVerifier(String floor) {
        return runtime("module-repair-verifier", steer("module-repair-verifier", floor),
                P_TROUBLESHOOT_LOOP_CRITIC);
    }

    Agent moduleRepairStepDoer() {
        return agent("module-repair-step-doer");
    }

    /** Judges the troubleshooting edit: migration fix, or gaming the gate? */
    Agent moduleRepairStepVerifier() {
        return agent("module-repair-step-verifier");
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

    Agent moduleRepairPlanner() {
        return runtime("module-repair-planner", steer("module-repair-planner", ""),
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
                define("module-repair-planner", "says what the campaign is for, and when it is over",
                        floors(P_TROUBLESHOOT_PLANNER), steer("module-repair-planner", "")),
                define("module-repair-verifier", "judges the campaign, not the step",
                        P_TROUBLESHOOT_LOOP_CRITIC, steer("module-repair-verifier", "")),
                define("module-repair-step-planner", "decides the next step, and when to stop",
                        P_TROUBLESHOOT_LOOP, steer("module-repair-step-planner", "")),
                define("module-repair-step-doer", "clears one wall the deterministic table did not know",
                        P_TROUBLESHOOTER, patch("module-repair-step-doer")),
                define("module-repair-step-verifier", "migration fix, or gaming the gate", P_TROUBLE_CRITIC,
                        read("module-repair-step-verifier")),
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
        // WHO IS SPEAKING, ANSWERABLE LATER. Two models serve all thirty-four agents, so a listener
        // under them cannot be told the name; the system prompt is what travels with the request,
        // and every agent's is distinct.
        Listening.register(name, prompt);
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
        // THE PROMPT ACTUALLY IN FORCE, which is what the listener will see on the wire. Registering
        // the built-in here instead would leave every edited agent unnamed in the record, and an
        // edited prompt is exactly the one a reader is trying to follow.
        Listening.register(name, prompt);
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
