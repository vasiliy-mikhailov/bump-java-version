package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.tool.ToolExecutor;
import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.llm.Asking;
import tech.mikhailov.ratchet.record.JsonlTrace;
import tech.mikhailov.ratchet.llm.Listening;
import tech.mikhailov.ratchet.llm.Model;
import tech.mikhailov.ratchet.config.Prompts;
import tech.mikhailov.ratchet.record.Trace;
import tech.mikhailov.ratchet.flow.Triad;
import tech.mikhailov.bjv.jvm.Declared;
import tech.mikhailov.bjv.jvm.Migrate;
import tech.mikhailov.bjv.jvm.Runner;
import tech.mikhailov.bjv.jvm.Tree;


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
public final class Agents {

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

    /**
     * THE ELEVEN DISTINCT TEXTS THE FOURTEEN MODULE-SCOPED AGENTS DRAW ON.
     *
     * <p>Eleven and not fourteen, by the rule stated just above: the two pin phases share
     * pins.md, pins-planner.md and pins-critic.md and differ only by substitution, so a file each
     * would be the same words twice. The platform fragments are keyed the same way, which is why
     * there are thirty-three of them rather than forty-two.
     */
    private static final List<String> MODULE_PROMPTS = List.of(
            // BEFORE AND AFTER SHARE A BODY AND NOT A FRAGMENT. One .md serves both pin phases,
            // differing by {WHEN} and {PINS}, and that is right: the mechanics of raising a version
            // do not change between them. The platform half does. Enabling a hop on a Boot module
            // is a question about what the set already gives you; hardening one is a question about
            // a CVE in a member of that set, where the obvious edit silences the scanner and takes
            // the artifact out of the set in the same stroke. Sharing the fragment made after-pins
            // read its own list through before-pins' argument.
            "pins-planner", "pins", "pins-critic",
            "after-pins-planner", "after-pins", "after-pins-critic",
            "bump-planner", "bumper", "bump-critic",
            "troubleshoot-planner", "troubleshoot-loop-critic", "troubleshoot-loop",
            "troubleshooter", "trouble-critic");

    /**
     * THE PLATFORM HALF OF EVERY MODULE-SCOPED PROMPT, HELD BY (platform, key).
     *
     * <p>Versions are managed in regimes whose knowledge is almost disjoint. On a Spring Boot
     * module you raise Boot and Tomcat, Jackson and Lombok move with it, and pinning one of them
     * directly overrides the managed set rather than following it; on a module nothing manages you
     * raise each artifact and own the conflicts. A prompt written for one regime is actively wrong
     * in the other.
     *
     * <p>COMPOSITION, NOT THIRTY-THREE STANDALONE PROMPTS. Four fifths of what a pin doer is told
     * is the same whatever manages it, and copying the whole text three times is how a file called
     * Chain came to say one thing while the program did another. That file was deleted rather than
     * kept in step, and this is the same decision made in advance.
     *
     * <p>Read at class initialisation, so a fragment that is missing or empty fails before any bump
     * starts rather than when a detector happens to answer quarkus on the ninth module of the
     * fortieth repository. {@link Bom} fails on an unreadable row for the same reason.
     */
    private static final Map<String, String> FRAGMENTS = fragments();

    private static Map<String, String> fragments() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String platform : Managed.PLATFORMS) {
            for (String key : MODULE_PROMPTS) {
                out.put(platform + "/" + key, text("platform/" + platform + "/" + key));
            }
        }
        return Map.copyOf(out);
    }

    /**
     * WHERE THE PLATFORM HALF GOES: a line of its own, so the seam is visible in the file.
     *
     * <p>The indentation of that line is kept and the fragment is re-indented to it. Four of these
     * eleven files were Java text blocks before the prompts moved to disk and still carry sixteen
     * spaces of their own, while the rest sit at the margin, so a fixed indent would leave one
     * group or the other looking like a paste.
     */
    private static final Pattern PLATFORM_SLOT =
            Pattern.compile("(?m)^([ \\t]*)\\{PLATFORM}[ \\t]*$");

    /**
     * One module-scoped prompt, composed for one platform.
     *
     * <p>A body with no slot in it throws rather than quietly shipping the shared four fifths on
     * their own. That failure would be invisible otherwise: the text reads perfectly well without
     * the missing half, and an agent inside the module walk that is told nothing about the regime
     * it is acting in is the exact thing this scheme exists to remove.
     */
    private static String withPlatform(String body, String key, String platform) {
        String fragment = FRAGMENTS.get(platform + "/" + key);
        if (fragment == null) {
            throw new IllegalStateException(
                    "no prompt fragment for " + key + " on platform " + platform);
        }
        Matcher slot = PLATFORM_SLOT.matcher(body);
        StringBuilder out = new StringBuilder();
        boolean placed = false;
        while (slot.find()) {
            placed = true;
            slot.appendReplacement(out,
                    Matcher.quoteReplacement(indented(fragment, slot.group(1))));
        }
        slot.appendTail(out);
        if (!placed) {
            // NAMES THE KEY, NOT A FILE, because the two parted company when after-pins got
            // a fragment of its own: its body is pins.md and its key is after-pins.
            throw new IllegalStateException("the body composed for fragment " + key + " has no "
                    + "{PLATFORM} line, so the platform half of it would never be said");
        }
        return out.toString();
    }

    /** A fragment at the slot's own margin, with whatever nesting it wrote of its own kept. */
    private static String indented(String fragment, String pad) {
        return fragment.stripIndent().strip().lines()
                .map(line -> line.isBlank() ? "" : pad + line)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * THE PLATFORM RIDES IN THE AGENT'S NAME, which is what keeps everything else keyed as it was.
     *
     * <p>{@link Prompts} stores an override per agent per hop, the settings page lists agents, and
     * the trace attributes a tool call to one. A before-pins doer written for a Boot module IS a
     * different agent from the one written for a module nothing manages: it is handed different
     * text and it will be edited apart. Saying that in the name says it once, instead of adding a
     * third key to the store, the page, the file layout and every lookup in between.
     */
    static String named(String agent, String platform) {
        return agent + "@" + platform;
    }

    /**
     * The bare agent behind a platform-keyed name, which is what the tree knows it as.
     *
     * <p>The shape is drawn before any module has been looked at, so no node can name a platform:
     * the walk says before-pins-planner and the catalogue holds three of them. Anything that joins
     * the two, the settings page's sort and the test that binds the catalogue to the tree, joins on
     * this.
     */
    public static String stem(String agent) {
        int at = agent.indexOf('@');
        return at < 0 ? agent : agent.substring(0, at);
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

    /**
     * THE DETECTOR'S OWN THREE, KEYED BY HOP AND NOT BY PLATFORM.
     *
     * <p>They run first inside the module walk, before anything knows which regime this module is
     * in, which is the one place in that walk where a platform-keyed prompt would be a question
     * answering itself.
     */
    private static final String P_PLATFORM_PLANNER = text("platform-planner");
    private static final String P_PLATFORM_DOER = text("platform-doer");
    private static final String P_PLATFORM_VERIFIER = text("platform-verifier");

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
    Agent bumpDoer(String platform) {
        return agent(named("bump-doer", platform));
    }

    /** Checks the landing: the pin grep is re-run for it, so it judges the state, not the claim. */
    Agent bumpVerifier(String platform) {
        return agent(named("bump-verifier", platform));
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
    Agent moduleRepairStepPlanner(String platform, String floor) {
        return steered(named("module-repair-step-planner", platform), floor);
    }

    /**
     * Judges the campaign, not the step, and can put the workspace back if it was the wrong campaign.
     *
     * <p>The step critic reviews one edit at a time and cannot see a run of individually reasonable
     * steps adding up to nothing, or a declaration of defeat that had a route left. This one reads
     * the whole thing and is the only agent that may send the loop back to where it started.
     */
    Agent moduleRepairVerifier(String platform, String floor) {
        return steered(named("module-repair-verifier", platform), floor);
    }

    /**
     * The two agents that hold the campaign's floor, taken from the catalogue rather than rebuilt
     * beside it.
     *
     * <p>They cannot come through {@link #agent} unchanged, because rewind_to has to be bounded by
     * the commit the campaign started from and that is not known until a campaign starts. What they
     * take from the catalogue is the PROMPT, which is the half that used to drift: the definition
     * and the factory each restated it, and these two already disagreed about the floor itself, the
     * catalogue describing a rewind bounded by nothing while the running agent was bounded by the
     * campaign.
     */
    private Agent steered(String name, String floor) {
        Definition d = definition(name);
        return runtime(d.name(), steer(d.name(), floor), d.systemPrompt());
    }

    Agent moduleRepairStepDoer(String platform) {
        return agent(named("module-repair-step-doer", platform));
    }

    /** Judges the troubleshooting edit: migration fix, or gaming the gate? */
    Agent moduleRepairStepVerifier(String platform) {
        return agent(named("module-repair-step-verifier", platform));
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
        return new Asking(Model.forRetry(trace), prompt, tools, "agent:" + name + ":retry",
                trace instanceof JsonlTrace j ? j : null);
    }

    /**
     * EVERY AGENT, AS DATA, BUILT FOR THIS HOP.
     *
     * <p>{@link Definition} is the shape, and this class used to hand-roll a worse one: sixteen
     * factory methods each assembled a name, a prompt and a tool set and then threw the assembly
     * away, which is why the prompts could only be read by opening this file and why there was no
     * way to ask what a different hop would be told.
     *
     * <p>As data they can be listed, rendered, diffed between hops, and composed. The order is the
     * order the chain reaches them.
     */

    /** One of the two pin phases, as a pair. The list in the prompt is the list the tool checks. */
    /** The steps in a phase that are not version pins, and only the before phase has any. */
    private String also(boolean after) {
        if (after) {
            // THE LIST IS NOT THE WHOLE JOB, and on this phase saying so is most of the job. The
            // floors name what somebody wrote down; the scan names what the project actually
            // resolved, and the second is longer. Measured over this corpus, 86 major.minor lines
            // carry a patch that clears something a project here was really carrying, and the bill
            // of materials has a row for every one of them. What it cannot do is reach the agent:
            // the row is data the scorer reads, and the only list you are handed is the one above.
            return """
                THE FLOORS ABOVE ARE NOT THE WHOLE JOB. They name what this hop needs. Your scan
                names what this project actually resolved, and anything in it carrying a CRITICAL or
                HIGH is worth the same move even when no line above mentions it.

                THE MOVE IS ALWAYS WITHIN THE LINE. For any such artifact, the floor is the head of
                the major.minor it is already on: call inspect_jar on the coordinates with NO
                version and it lists every version in the mirror, the highest one sharing that
                major.minor is the patch, and bump_patch lands it. Crossing a minor is a different
                move and it is not this phase.

                THIS REACHES ALL THREE OF YOU, and it has to. A plan that names only the floors
                above leaves the rest unreachable, because the hand that edits is told to raise what
                the plan names and leave everything else. So the plan names these too, the doer
                raises what the plan names, and an artifact patched to the head of its own line is
                NOT unasked work to object to.

                Measured on this corpus: netty 4.1 goes 9 CRITICAL+HIGH to 0, logback 1.2 goes 2 to
                0, xstream 1.4 goes 20 to 0, tomcat 10.1 goes 21 to 0. None of those needs a new
                dependency and none changes an API.

                ON A MANAGED MODULE, RAISE THE PLATFORM FIRST. Most of these artifacts arrive
                through Spring Boot or Quarkus, and the platform raise moves them as a set. Pin one
                directly only where it is STILL below the head of its line after that, which is the
                one condition under which writing a version onto a managed artifact is right.
                """;
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
    private String bumpPrompt(String platform) {
        StringBuilder recipes = new StringBuilder();
        recipes.append("- org.openrewrite.java.migrate.UpgradePluginsForJava")
                .append(hop.to()).append('\n')
                .append("- org.openrewrite.java.migrate.UpgradeBuildToJava")
                .append(hop.to()).append('\n')
                .append("- org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo\n");
        if (hop.crosses(11)) {
            recipes.append("- org.openrewrite.java.migrate.Java8toJava11 "
                    + "— the only recipe that handles the modules JEP 320 removed, and this hop "
                    + "crosses rung 11\n");
        }
        recipes.append("- org.openrewrite.gradle.UpdateJavaCompatibility with "
                + "version ").append(hop.to()).append(" — the Gradle half, a no-op on Maven\n");
        return withPlatform(P_BUMPER, "bumper", platform)
                .replace("{RECIPES}", recipes.toString())
                .replace("{FROM}", String.valueOf(hop.from()))
                .replace("{TARGET}", String.valueOf(hop.to()));
    }

    private String pinPrompt(String base, boolean after) {
        return base.replace("{ALSO}", also(after)).replace("{PINS}", (after ? Floors.after(hop.to()) : Floors.before(hop.to()))
                        .lines().map(l -> "- " + l.strip())
                        .collect(java.util.stream.Collectors.joining("\n")))
                .replace("{FROM}", String.valueOf(hop.from()))
                .replace("{TARGET}", String.valueOf(hop.to()))
                // WHAT EACH PHASE IS FOR, NOW THAT ALMOST EVERYTHING HAPPENS IN THE SECOND ONE.
                // The after brief used to say these versions REQUIRE the new JDK, which is true of
                // Spring Boot 3 and false of mockito, hamcrest and most of the list: they were
                // simply deferred, because the module gate compiles before this phase runs and
                // nothing that only matters to a test run needs to be in place for a compile.
                .replace("{WHEN}", after
                        ? "The JDK has already been raised and this module compiles under it. "
                        + "Nearly every floor is raised here, because only what javac itself "
                        + "cannot start without was done before the bump. So this list is the "
                        + "long one, and it holds what the test run will need as well as what "
                        + "the scanner will."
                        : "The JDK has NOT been raised yet, and this list is deliberately short: "
                        + "it holds only what javac cannot start without. Everything else waits "
                        + "until the module compiles, where a version is cheaper to raise and "
                        + "easier to attribute when it breaks something.");
    }

    /**
     * THE MODULE-SCOPED AGENTS, EACH ASKED FOR BY THE PLATFORM ITS MODULE IS IN.
     *
     * <p>Four of these used to restate a prompt and a tool set instead of asking the catalogue for
     * one, which is the thing {@code TheChainIsDeclaredOnceTest} exists to stop: the settings page
     * reads the catalogue, so a factory that builds something else describes an agent nobody runs.
     * They resolve through {@link #agent} now, and the two that genuinely cannot go through
     * {@link #steered} instead.
     */
    Agent beforePinsDoer(String platform) {
        return agent(named("before-pins-doer", platform));
    }

    Agent beforePinsVerifier(String platform) {
        return agent(named("before-pins-verifier", platform));
    }

    Agent afterPinsDoer(String platform) {
        return agent(named("after-pins-doer", platform));
    }

    Agent afterPinsVerifier(String platform) {
        return agent(named("after-pins-verifier", platform));
    }

    /**
     * WHICH REGIME MANAGES THIS MODULE, asked first and answered by a pair like everything else.
     *
     * <p>Hop-keyed rather than platform-keyed, because these three run before the platform is
     * known. Their facts are {@link Managed#report} and {@link Declared#report}, which state what
     * the build files say without judging any of it; the judging is the doer's, and the closed set
     * of three words it may answer is what the rest of the walk is keyed by.
     */
    Agent platformPlanner() {
        return agent("platform-planner");
    }

    Agent platformDoer() {
        return agent("platform-doer");
    }

    Agent platformVerifier() {
        return agent("platform-verifier");
    }

    /**
     * The planners: they read and decide, and they hold no tool that writes.
     *
     * <p>Separating them from the doers is what lets a verifier say `replan` and mean something. A
     * producer that both chose the approach and carried it out has no way to be told the approach
     * was wrong, only that the attempt was, so an objection to the plan came back as another attempt
     * at the same plan until the budget ran out.
     */
    Agent beforePinsPlanner(String platform) {
        return agent(named("before-pins-planner", platform));
    }

    Agent afterPinsPlanner(String platform) {
        return agent(named("after-pins-planner", platform));
    }

    Agent bumpPlanner(String platform) {
        return agent(named("bump-planner", platform));
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
        return agent("modules-planner");
    }

    Agent modulesVerifier() {
        return agent("modules-verifier");
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

    Agent moduleRepairPlanner(String platform) {
        return agent(named("module-repair-planner", platform));
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

    /**
     * SIXTY-FIVE, BECAUSE FOURTEEN OF THEM EXIST ONCE PER PLATFORM.
     *
     * <p>Twenty agents run above the module walk and are keyed by hop alone: what a surveyor or an
     * estimator is told does not depend on what manages any one module's versions. Three more are
     * the detector, also hop-keyed, because they run before the platform is known. The remaining
     * fourteen run inside the walk and are defined once per platform under the name
     * {@code <name>@<platform>}, which is 20 + 3 + 14 * 3.
     *
     * <p>THE ORDER IS STILL THE ORDER THE CHAIN REACHES THEM, with each module-scoped agent's three
     * platforms sitting together where the bare one used to sit. The settings page sorts by the
     * tree's walk and the tree can only ever name the bare fourteen, so the platforms of one agent
     * have to arrive as a run or the page interleaves them with the next stage's.
     */
    List<Definition> definitions() {
        List<Definition> out = new ArrayList<>();
        out.add(define("survey-planner", "says what would settle which JDK this project is on",
                floors(P_SURVEY_PLANNER), read("survey-planner")));
        out.add(define("survey-doer", "reads what JDK the project is actually on", P_SURVEYOR,
                read("survey-doer")));
        out.add(define("survey-verifier", "checks the survey against the build files",
                P_SURVEY_CRITIC, read("survey-verifier")));
        out.add(define("security-before-planner", "says which CVE families this hop could move",
                floors(P_SECURITY_PLANNER), read("security-before-planner")));
        out.add(define("security-before-doer", "reads the pre-bump vulnerability scan",
                P_SECURITY_BEFORE, read("security-before-doer")));
        out.add(define("security-before-verifier", "checks that reading", P_SECURITY_BEFORE_CRITIC,
                read("security-before-verifier")));
        out.add(define("module-filter-planner",
                "says where to look for a vendored or generated module",
                floors(P_MODULE_FILTER_PLANNER), read("module-filter-planner")));
        out.add(define("module-filter-doer", "says which modules this bump should leave alone",
                floors(P_MODULE_FILTER), read("module-filter-doer")));
        out.add(define("module-filter-verifier", "checks every skip is evidenced",
                floors(P_MODULE_FILTER_CRITIC), read("module-filter-verifier")));
        out.add(define("modules-planner",
                "says what one module needs, and nothing about its siblings",
                floors(P_MODULE_PLANNER), Tools.judging(ws, tree, trace, "modules-planner")));

        // FIRST INSIDE THE WALK, AND THE ONLY STAGE IN IT THAT IS NOT PLATFORM-KEYED. Everything
        // after this one is chosen by the word this one settles on.
        out.add(define("platform-planner", "says what would settle which regime manages a module",
                floors(P_PLATFORM_PLANNER), Tools.judging(ws, tree, trace, "platform-planner")));
        out.add(define("platform-doer", "names what manages this module's dependency versions",
                floors(P_PLATFORM_DOER), Tools.judging(ws, tree, trace, "platform-doer")));
        out.add(define("platform-verifier", "checks that word against what the build files say",
                floors(P_PLATFORM_VERIFIER), Tools.judging(ws, tree, trace, "platform-verifier")));

        perPlatform(out, "before-pins-planner", "decides which pins to raise, in which module",
                p -> pinPrompt(withPlatform(P_PINS_PLANNER, "pins-planner", p), false),
                n -> Tools.judging(ws, tree, trace, n));
        perPlatform(out, "before-pins-doer",
                "raises the versions the new JDK needs, before it moves",
                p -> pinPrompt(withPlatform(P_PINS, "pins", p), false),
                n -> Tools.pinning(ws, recipes(), tree, String.valueOf(hop.from()), trace, n));
        perPlatform(out, "before-pins-verifier",
                "checks every pre-JDK pin landed, module by module",
                p -> pinPrompt(withPlatform(P_PINS_CRITIC, "pins-critic", p), false),
                n -> Tools.judging(ws, tree, trace, n));
        perPlatform(out, "bump-planner", "groups the remaining target declarations by module",
                p -> floors(withPlatform(P_BUMP_PLANNER, "bump-planner", p)),
                n -> Tools.checking(ws, tree, targetJdk, trace, n));
        perPlatform(out, "bump-doer", "moves the project to the target JDK",
                this::bumpPrompt,
                n -> Tools.raising(ws, runner, recipes(), tree, targetJdk,
                        String.valueOf(hop.to()), trace, n));
        perPlatform(out, "bump-verifier", "checks every module reached the target",
                p -> floors(withPlatform(P_BUMP_CRITIC, "bump-critic", p)),
                n -> Tools.checking(ws, tree, targetJdk, trace, n));
        perPlatform(out, "module-repair-planner",
                "says what the campaign is for, and when it is over",
                p -> floors(withPlatform(P_TROUBLESHOOT_PLANNER, "troubleshoot-planner", p)),
                n -> steer(n, ""));
        perPlatform(out, "module-repair-verifier", "judges the campaign, not the step",
                p -> floors(withPlatform(P_TROUBLESHOOT_LOOP_CRITIC, "troubleshoot-loop-critic", p)),
                n -> steer(n, ""));
        perPlatform(out, "module-repair-step-planner", "decides the next step, and when to stop",
                p -> floors(withPlatform(P_TROUBLESHOOT_LOOP, "troubleshoot-loop", p)),
                n -> steer(n, ""));
        perPlatform(out, "module-repair-step-doer",
                "clears one wall the deterministic table did not know",
                p -> floors(withPlatform(P_TROUBLESHOOTER, "troubleshooter", p)),
                this::patch);
        perPlatform(out, "module-repair-step-verifier", "migration fix, or gaming the gate",
                p -> floors(withPlatform(P_TROUBLE_CRITIC, "trouble-critic", p)),
                this::read);
        perPlatform(out, "after-pins-planner",
                "decides which post-JDK pins to raise, in which module",
                p -> pinPrompt(withPlatform(P_PINS_PLANNER, "after-pins-planner", p), true),
                n -> Tools.judging(ws, tree, trace, n));
        perPlatform(out, "after-pins-doer", "raises the versions that only run on the new JDK",
                p -> pinPrompt(withPlatform(P_PINS, "after-pins", p), true),
                n -> Tools.pinning(ws, recipes(), tree, String.valueOf(hop.to()), trace, n));
        perPlatform(out, "after-pins-verifier",
                "checks every post-JDK pin landed, module by module",
                p -> pinPrompt(withPlatform(P_PINS_CRITIC, "after-pins-critic", p), true),
                n -> Tools.judging(ws, tree, trace, n));

        out.add(define("modules-verifier", "closes one module, or sends it back",
                floors(P_MODULE_VERIFIER), Tools.judging(ws, tree, trace, "modules-verifier")));
        out.add(define("security-after-planner", "says which findings the bump could have moved",
                floors(P_SECURITY_PLANNER), read("security-after-planner")));
        out.add(define("security-after-doer",
                "reads what the bump did to the vulnerability count",
                P_SECURITY_AFTER, read("security-after-doer")));
        out.add(define("security-after-verifier", "checks that judgement", P_SECURITY_AFTER_CRITIC,
                read("security-after-verifier")));
        out.add(define("verdict-planner", "names the one question execution could not settle",
                floors(P_VERDICT_PLANNER), read("verdict-planner")));
        out.add(define("verdict-doer", "argues an unsettled bump into the corpus vocabulary",
                P_VERDICT, read("verdict-doer")));
        out.add(define("verdict-verifier", "checks that word against what actually happened",
                P_VERDICT_CRITIC, read("verdict-verifier")));
        out.add(define("estimator-planner", "lists the distinct pieces of work that landed",
                floors(P_ESTIMATOR_PLANNER), read("estimator-planner")));
        out.add(define("estimator-doer", "prices the work a developer would have done", P_ESTIMATOR,
                read("estimator-doer")));
        out.add(define("estimator-verifier", "checks the price against the work in the log",
                P_ESTIMATOR_CRITIC, read("estimator-verifier")));
        return List.copyOf(out);
    }

    /**
     * One module-scoped agent, defined once for each platform.
     *
     * <p>The tool set is built from the KEYED NAME rather than from a literal, which removes a
     * hazard the flat list carried and would have tripled. Every {@code Tools.*} factory takes the
     * agent's name as its last argument, for trace attribution, and those literals were typed by
     * hand at each definition and again at each accessor that restated one. A trace attributing a
     * call to an agent nobody defined is a record that cannot be followed back.
     */
    private void perPlatform(List<Definition> out, String name, String description,
                             Function<String, String> prompt,
                             Function<String, Map<ToolSpecification, ToolExecutor>> tools) {
        for (String platform : Managed.PLATFORMS) {
            String keyed = named(name, platform);
            out.add(define(keyed, description, prompt.apply(platform), tools.apply(keyed)));
        }
    }

    /**
     * The definitions for a hop, without a model or a workspace: for reading rather than running.
     *
     * <p>The settings page asks what a 17-to-21 bump will be told before one is running, which is
     * the whole point of being able to see them.
     */
    public static List<Definition> forHop(Hop hop, Path ws) {
        return new Agents(null, null, ws, null, new Tree(ws, note -> { }), hop, null).definitions();
    }

    /** One definition, and the one place a name, a description, a prompt and a tool set are joined. */
    private Definition define(String name, String description, String prompt,
                              Map<ToolSpecification, ToolExecutor> tools) {
        // WHO IS SPEAKING, ANSWERABLE LATER. Two models serve all sixty-five agents, so a listener
        // under them cannot be told the name; the system prompt is what travels with the request,
        // and every agent's is distinct, the platform-keyed ones included: the fragment that
        // differs is what makes each of a stem's three a different text.
        Listening.register(name, prompt);
        return new Definition(name, description, prompt, tools);
    }

    /** The definition by name, which is how the chain asks for one. */
    Definition definition(String name) {
        return definitions().stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no agent called " + name));
    }

    /** A runnable agent from its definition. */
    private Agent agent(String name) {
        Definition d = definition(name);
        return runtime(d.name(), d.tools(), d.systemPrompt());
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
        String edited = Prompts.override(name, hop.key());
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
        Asking runtime = new Asking(judges ? judging : model, prompt, tools, "agent:" + name,
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
