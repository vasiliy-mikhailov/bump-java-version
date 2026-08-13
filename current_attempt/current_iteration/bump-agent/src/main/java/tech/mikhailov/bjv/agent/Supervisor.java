package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.deepagents.langchain4j.logging.ToolInvocationLogMode;
import com.deepagents.langchain4j.subagents.SubAgentRuntime;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * THE ONE AGENT THAT STANDS OUTSIDE A BUMP.
 *
 * <p>Every other agent here reasons about the bump it lives in, which is right for the work and
 * blind to everything that only appears across runs. This session alone: a critic that returned an
 * empty answer and was retried into approving a stub it had rejected an hour earlier; a lane that
 * spent fifty-nine minutes on gate turn one; a troubleshooter inventing Gradle versions; ten staged
 * distributions poisoning themselves with half-finished downloads. Not one of those is visible from
 * inside a single bump, and every one of them cost more than any single bad bump did.
 *
 * <p>A proposer and a critic, like everything else here. The proposer reads the sweep and says what
 * looks wrong; the critic decides whether each finding holds, because a supervisor that reports
 * everything it notices is noise and a supervisor nobody checks is worse. Findings accumulate in a
 * file rather than a context window, so what it noticed an hour ago is still there when the same
 * shape recurs.
 *
 * <p>IT MAY SET A LANE ASIDE AND IT MAY NOT SETTLE ONE. Postponing stops a lane that is going
 * nowhere and frees its slot; it never writes a verdict, never edits a workspace, and never decides
 * whether a bump passed. The gate does that, and a supervisor that could overrule it would be a way
 * to make the numbers say anything.
 */
final class Supervisor {

    /** How long between rounds. Long, because the thing being watched moves in tens of minutes. */
    private static final Duration EVERY = Duration.ofMinutes(
            Long.parseLong(System.getenv().getOrDefault("BJV_SUPERVISOR_MINUTES", "20")));

    private final Sweep sweep;
    private final Path findings;
    private final Trace trace;
    private final ChatModel proposing;
    private final ChatModel judging;

    Supervisor(Path results, Trace trace) {
        this.sweep = new Sweep(results);
        this.findings = results.resolve("findings.jsonl");
        this.trace = trace;
        this.proposing = Model.forProducer(trace);
        this.judging = Model.forCritic(trace);
    }

    public static void main(String[] args) throws Exception {
        Path results = Path.of(args.length > 0 ? args[0]
                : "/home/vmihaylov/bump-java-version/current_attempt/current_iteration/runs_agent/results");
        Trace trace = new JsonlTrace(results.resolve("supervisor.jsonl"),
                results.resolve("settlements.jsonl"), "supervisor");
        Supervisor supervisor = new Supervisor(results, trace);
        System.out.println("supervisor watching " + results + ", every " + EVERY.toMinutes() + "m");
        while (true) {
            try {
                supervisor.round();
            } catch (RuntimeException | IOException surviving) {
                // A supervisor that dies on a bad round stops watching, which is worse than a bad
                // round. Every failure here is one it can try again in twenty minutes.
                System.out.println("round failed: " + surviving);
            }
            Thread.sleep(EVERY.toMillis());
        }
    }

    // ---- one round ----

    void round() throws IOException {
        long now = System.currentTimeMillis();
        String digest = sweep.digest(now);
        String open = openFindings();

        String proposed = agent("supervisor", proposing, recorded(tools(now), "supervisor"), PROPOSER)
                .run("The sweep as it stands:\n\n" + digest
                        + "\n\nWhat you have already reported and that still holds:\n"
                        + (open.isBlank() ? "  nothing yet" : open));

        for (String claim : claims(proposed)) {
            String judgement = agent("supervisor-critic", judging, recorded(tools(now), "supervisor-critic"), CRITIC)
                    .run("The sweep as it stands:\n\n" + digest
                            + "\n\nA colleague reports:\n" + claim
                            + "\n\nDoes it hold?");
            String verdict = Bump.word(judgement, "holds", "refuted");
            record(claim, verdict, judgement);
        }
    }

    /** One finding per line: the proposer is told to write them that way and mostly does. */
    private static List<String> claims(String reply) {
        List<String> out = new ArrayList<>();
        for (String line : reply.split("\n")) {
            String s = line.strip().replaceFirst("^[-*\\d.)\\s]+", "").strip();
            if (s.length() > 25 && !s.toLowerCase(java.util.Locale.ROOT).startsWith("nothing")) {
                out.add(s);
            }
        }
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    private void record(String claim, String verdict, String judgement) throws IOException {
        String row = "{\"at\":\"" + System.currentTimeMillis() + "\",\"finding\":\""
                + escape(claim) + "\",\"verdict\":\"" + verdict + "\",\"why\":\""
                + escape(judgement.length() > 900 ? judgement.substring(0, 900) : judgement) + "\"}\n";
        Files.writeString(findings, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("[" + verdict + "] " + claim);
    }

    /** What has been reported and survived, so the proposer neither repeats nor forgets it. */
    private String openFindings() {
        if (!Files.isReadable(findings)) {
            return "";
        }
        List<String> held = new ArrayList<>();
        try (var rows = Files.lines(findings)) {
            for (String row : rows.toList()) {
                if ("holds".equals(Sweep.text(row, "verdict"))) {
                    held.add("  - " + Sweep.text(row, "finding"));
                }
            }
        } catch (IOException unreadable) {
            return "";
        }
        int from = Math.max(0, held.size() - 20);
        return String.join("\n", held.subList(from, held.size()));
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", " ").replace("\t", " ");
    }

    // ---- what it can do ----

    /**
     * Every tool call, in the trace.
     *
     * <p>Tools.java wraps its executors so the corpus sees them; this class built its own raw, so
     * two bumps were postponed with correct reasons and the trace recorded no postpone call at all.
     * A supervisor whose actions leave no record is the one thing here that must not have one.
     */
    private Map<ToolSpecification, ToolExecutor> recorded(Map<ToolSpecification, ToolExecutor> raw,
                                                          String agent) {
        Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
        raw.forEach((spec, executor) -> wrapped.put(spec, (request, memoryId) -> {
            String result = executor.execute(request, memoryId);
            trace.tool(agent, spec.name(), request.arguments(), result);
            return result;
        }));
        return wrapped;
    }

    private Map<ToolSpecification, ToolExecutor> tools(long now) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();

        tools.put(ToolSpecification.builder()
                .name("bumps")
                .description("Every bump this sweep has started: where each one is, how long it has "
                        + "been running, how long since it last wrote anything, and how it settled. "
                        + "The digest you were handed is the summary; this is all of it.")
                .parameters(JsonObjectSchema.builder().build())
                .build(), (request, memoryId) -> sweep.digest(now));

        tools.put(ToolSpecification.builder()
                .name("read_bump")
                .description("One bump's event log, one line per event: what each agent answered, "
                        + "what each critic objected to, what each tool returned. This is where a "
                        + "claim about a bump is checked rather than inferred from its timings.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("repo", "owner/repo, as shown in the digest")
                        .addStringProperty("agent", "optional, to see only one agent's answers")
                        .addIntegerProperty("limit", "how many recent events, default 60")
                        .required("repo")
                        .build())
                .build(), (request, memoryId) -> {
                    String repo = Reasoning.field(request.arguments(), "repo");
                    String who = Reasoning.field(request.arguments(), "agent");
                    int limit = Reasoning.number(request.arguments(), "limit", 60);
                    return sweep.lanes().stream()
                            .filter(l -> l.repo().equalsIgnoreCase(repo))
                            .findFirst()
                            .map(l -> new JsonlTrace(sweep.results().resolve(l.slug())
                                    .resolve("trace.jsonl"), null, l.bump())
                                    .happened("", who, limit))
                            .filter(s -> !s.isBlank())
                            .orElse("no bump called " + repo + " has started yet");
                });

        tools.put(ToolSpecification.builder()
                .name("postpone")
                .description("Set a bump aside: stop it if it is running, and keep the launcher from "
                        + "starting it again while anything else is still waiting. For a lane that "
                        + "is going nowhere -- silent for a long time, or circling the same failure "
                        + "-- so its slot goes to work that can progress. It is not a verdict: the "
                        + "bump keeps whatever it had and is retried once the queue is otherwise "
                        + "empty. Say plainly why, because that reason is what someone reads later.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("repo", "owner/repo, as shown in the digest")
                        .addStringProperty("why", "what makes this one worth setting aside")
                        .required("repo", "why")
                        .build())
                .build(), (request, memoryId) -> {
                    String repo = Reasoning.field(request.arguments(), "repo");
                    String why = Reasoning.field(request.arguments(), "why");
                    if (why.isBlank()) {
                        return "REFUSED: a postponement without a reason is indistinguishable from "
                                + "a bump nobody ran. Say what is wrong with this one.";
                    }
                    var lane = sweep.lanes().stream()
                            .filter(l -> l.repo().equalsIgnoreCase(repo)).findFirst();
                    if (lane.isEmpty()) {
                        return "no bump called " + repo + " has started yet";
                    }
                    if (lane.get().settled()) {
                        return "REFUSED: " + repo + " already settled as " + lane.get().state()
                                + ". A settled bump is finished, and setting it aside would only "
                                + "hide a verdict that has already been earned.";
                    }
                    if (!lane.get().live()) {
                        // POSTPONING A DEAD LANE IS THE OPPOSITE OF USEFUL. It holds no slot, so
                        // nothing is freed, and the launcher retries an unsettled bump on its own
                        // -- which the marker then prevents. The first fifteen postponements were
                        // every one of them a lane that had already died, which delayed exactly the
                        // work it was meant to unblock.
                        return "REFUSED: " + repo + " is not running. It holds no slot, so setting "
                                + "it aside frees nothing, and the launcher already retries an "
                                + "unsettled bump by itself -- a postponement would only delay "
                                + "that. Postpone what is RUNNING and going nowhere. If this one "
                                + "keeps dying in the same place, that is worth reporting as a "
                                + "finding instead.";
                    }
                    try {
                        sweep.postpone(lane.get().slug(), why);
                    } catch (IOException e) {
                        return "could not record the postponement: " + e.getMessage();
                    }
                    String stopped = stop(lane.get().bump());
                    trace.progress("supervisor", "postponed " + repo + ": " + why);
                    return "set aside: " + repo + ". " + stopped
                            + " The launcher will skip it until nothing else is left to run.";
                });

        tools.put(ToolSpecification.builder()
                .name("resume")
                .description("Undo a postponement, so the launcher may pick that bump up again.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("repo", "owner/repo, as shown in the digest")
                        .required("repo")
                        .build())
                .build(), (request, memoryId) -> {
                    String repo = Reasoning.field(request.arguments(), "repo");
                    var lane = sweep.lanes().stream()
                            .filter(l -> l.repo().equalsIgnoreCase(repo)).findFirst();
                    if (lane.isEmpty()) {
                        return "no bump called " + repo;
                    }
                    try {
                        return sweep.resume(lane.get().slug())
                                ? repo + " is back in the queue"
                                : repo + " was not postponed";
                    } catch (IOException e) {
                        return "could not resume: " + e.getMessage();
                    }
                });
        return tools;
    }

    /** Stop a lane's container. The claim releases itself once no agent holds it. */
    private String stop(String bump) {
        String name = sweep.containerFor(bump);
        if (name.isEmpty()) {
            return "It was not running.";
        }
        try {
            Shell.Output out = Shell.run(sweep.results(), Map.of(), Duration.ofMinutes(2),
                    "docker", "rm", "-f", name);
            return out.ok() ? "Its container (" + name + ") was stopped."
                    : "Its container could not be stopped.";
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Its container could not be stopped: " + e.getMessage();
        }
    }

    private Agents.Agent agent(String name, ChatModel model,
                               Map<ToolSpecification, ToolExecutor> tools, String prompt) {
        var runtime = new SubAgentRuntime(model, prompt, tools, "agent:" + name,
                ToolInvocationLogMode.NONE, trace instanceof JsonlTrace j ? j : null);
        return task -> {
            String reply = attempt(runtime, task);
            if (reply.isBlank()) {
                // THE SAME EMPTY ANSWER EVERY OTHER AGENT HERE GUARDS AGAINST, and this runner was
                // written without the guard: both of the supervisor's first two rounds returned
                // nothing and recorded nothing, which reads exactly like a fleet with no problems.
                // A blank means the reasoning entered a cycle greedy decoding cannot leave, so the
                // retry asks a model that does not reason at all.
                trace.progress("supervisor", name + " answered nothing; asking once more");
                reply = attempt(new SubAgentRuntime(Model.forRetry(trace), prompt, tools,
                        "agent:" + name, ToolInvocationLogMode.NONE,
                        trace instanceof JsonlTrace j2 ? j2 : null), task);
            }
            trace.asked(name, prompt + "\n\n---\n\n" + task, reply);
            return reply;
        };
    }

    /**
     * One try, and the reason it failed if it did.
     *
     * <p>SWALLOWING THE EXCEPTION MADE A BROKEN SUPERVISOR LOOK LIKE A CLEAN FLEET. It ran for the
     * better part of an hour with no credentials at all -- the launcher maps PROPOSER_API_KEY onto
     * OC_KEY for its lanes and the supervisor's own deployment did not -- and every model call
     * threw, was caught here, and was returned as an empty answer. An empty answer from a
     * supervisor reads as "nothing is wrong", which is the most expensive thing it could possibly
     * say by mistake.
     */
    private String attempt(SubAgentRuntime runtime, String task) {
        try {
            String reply = runtime.run(task);
            return reply == null ? "" : reply;
        } catch (RuntimeException e) {
            trace.progress("supervisor", "model call failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
            System.out.println("model call failed: " + e);
            return "";
        }
    }

    // ---- what they are asked ----

    private static final String PROPOSER = """
            You watch a fleet of automated Java version bumps, from outside them. Each bump is a \
            chain of agents working one repository; you are the only one who sees all of them at \
            once, and the failures worth your attention are the ones that are invisible from inside \
            a single run.

            What that looks like in practice: an agent that answers in one word, or not at all, \
            where reasoning was required. A critic whose objection is ignored by whatever comes \
            next. A lane that has written nothing for a long time. The same wall cleared by hand in \
            repository after repository, which means the deterministic table should have learned it. \
            A verdict that does not match what the log shows happening. A stage that is reached far \
            more often than the stage before it, which is impossible and means something is \
            miscounted.

            Check before you report. read_bump shows you what actually happened in a run, and a \
            claim you have not checked there is a guess. Timings alone are weak: a lane silent for \
            forty minutes may be waiting on a build, and one that has written constantly may be \
            circling.

            SET LANES ASIDE. That is half your job and the half that changes anything: a finding is \
            a note for later, while postpone frees a slot now. A bump keeps whatever it had and is \
            tried again once the queue is otherwise empty, so this costs a repository nothing.

            Only a RUNNING lane, though. Postponing one that has already died frees nothing, because \
            it holds no slot, and the launcher retries an unsettled bump by itself unless a marker \
            stops it -- so the postponement delays the very work it was meant to unblock. The digest \
            separates the two lists for exactly this reason. A bump that keeps dying in the same \
            place is a finding, not a postponement.

            Slow is not the same as stuck, and the difference is in the log rather than the clock. A \
            lane at four hours making progress -- new stages, new walls cleared, a gate turn that \
            moved -- is working, and builds here genuinely take tens of minutes. A lane at four \
            hours on the same stage, with an attempt count in double figures, or repeating an edit \
            its critic has already rejected, is not going to finish: it will burn its slot until \
            something kills it. Read the log with read_bump and say which one you are looking at.

            When it is the second kind, postpone it. Do not report a lane as looping and leave it \
            looping; that is the finding and the fix in the same breath, and you have both.

            Report ONE FINDING PER LINE, each a single sentence naming the specific thing that is \
            wrong and where you saw it. No preamble, no numbering, no summary at the end. Do not \
            repeat a finding you were shown as already holding unless you have something new about \
            it. If nothing is wrong, answer exactly: nothing to report.
            """;

    private static final String CRITIC = """
            A colleague watching a fleet of automated Java version bumps reports something wrong. \
            You decide whether it holds.

            Check it yourself. read_bump shows what actually happened in any run, and bumps shows \
            the fleet. A finding that sounds plausible and is not in the logs is worse than no \
            finding, because it sends someone to fix a thing that is not happening.

            Be careful with three shapes in particular. A slow lane is not a stalled one: builds \
            here take tens of minutes and thinking leaves no trace at all, so silence is not \
            evidence on its own. A count that looks impossible usually is, and is worth confirming \
            rather than dismissing. And a claim about what an agent said needs the words it said, \
            not a characterisation of them.

            Answer `holds`, with the evidence you found, or `refuted`, with what the logs actually \
            show. If you cannot check it either way, answer `refuted`: an unverified finding is a \
            rumour, and this list is only worth reading if everything on it survived being checked.
            """;
}
