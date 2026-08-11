package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE ORDER. Investigation belongs to the agents; sequence belongs here, where nothing can rewrite it.
 *
 * <p>The pipeline is the one the measurements chose: baseline, then the deterministic migration
 * (recipes, floors, target propagation — supplied as a script, because its content is the skill's
 * business, not this class's), then a bounded reflect loop in which the FREE things go first. Each
 * turn tries the mechanized wall table before spending a model call, and the model's edit is judged
 * before the next build spends nineteen hundred seconds on it.
 *
 * <p>THE GATE IS NOT A TOOL. No agent may invoke the runner: whether the gate ran after an edit is
 * not a model's choice. This class runs it, every turn, and what the gate says is what the record
 * says — {@code settle} computes the outcome from the builds wherever they established one, and the
 * verdict agent is asked only where they established nothing, because routing a deterministic
 * outcome through a model turns it into a sampled one.
 */
public final class Bump {

    /** Turns of the reflect loop. Rung-1's recovered repos took 4-9 iterations; the mean was 6. */
    private static final int TURNS = 8;
    /** One re-ask per objection, quoting whoever objected. Stated once, both loops share it. */
    private static final int REASK = 1;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Bump <checkout> <repo|sha|from|to> [results-dir] [migrate-script]");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        String bump = args[1];
        Path results = Path.of(args.length > 2 ? args[2] : "results");
        String migrate = args.length > 3 ? args[3] : null;

        String slug = bump.replaceAll("[^A-Za-z0-9]+", "_");
        JsonlTrace trace = new JsonlTrace(results.resolve(slug).resolve("trace.jsonl"),
                results.resolve("settlements.jsonl"), bump);
        try {
            String hoptools = System.getenv().getOrDefault("BJV_HOPTOOLS",
                    "/home/vmihaylov/bump-java-version/current_attempt/current_iteration/hoptools");
            Bump b = new Bump(checkout, bump, new Agents(Llm.fromEnv(), trace),
                    new Runner(checkout, hoptools), new Walls(checkout), trace, migrate);
            String account = b.run();
            String state = account.split("\n", 2)[0];
            trace.settled(bump, state, account, b.baselineGreen, b.gateGreen);
        } catch (RuntimeException e) {
            trace.failed(bump, e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private final Path ws;
    private final String bump;
    private final String from;
    private final String to;
    private final Agents agents;
    private final Runner runner;
    private final Walls walls;
    private final Trace trace;
    private final String migrate;
    // What the builds actually did, carried to the settlement. An implication is not a record.
    private boolean baselineGreen;
    private boolean gateGreen;

    private Bump(Path ws, String bump, Agents agents, Runner runner, Walls walls, Trace trace,
                 String migrate) {
        String[] parts = bump.split("\\|");
        if (parts.length < 4) {
            throw new IllegalArgumentException("bump must be repo|sha|from|to, got: " + bump);
        }
        this.ws = ws;
        this.bump = bump;
        this.from = parts[2];
        this.to = parts[3];
        this.agents = agents;
        this.runner = runner;
        this.walls = walls;
        this.trace = trace;
        this.migrate = migrate;
    }

    /** The whole bump. Read it top to bottom; that is the order, and nothing can reorder it. */
    private String run() throws IOException {
        trace.progress(bump, "baseline: building and testing under JDK " + from);
        Runner.Result preBuild = runner.build(from);
        trace.built("baseline-build", preBuild);
        if (preBuild.infra()) {
            return "no-baseline\nthe project does not build under its own JDK " + from + ":\n"
                    + preBuild.summary();
        }
        Runner.Result preTest = runner.test(from);
        trace.built("baseline-test", preTest);
        baselineGreen = !preTest.infra() && preTest.passed();
        if (!baselineGreen) {
            return "no-baseline\nthe tests are not green under the project's own JDK " + from
                    + ", so conservation cannot be judged:\n" + preTest.summary();
        }

        if (migrate != null) {
            trace.progress(bump, "migrate: recipes, floors, target propagation");
            try {
                Shell.Output out = Shell.run(ws, Map.of("BJV_FROM", from, "BJV_TO", to),
                        Duration.ofSeconds(2700), migrate, ws.toString(), from, to);
                trace.applied("migrate", Runner.tail(out.text()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("migrate interrupted", e);
            }
        }

        // THE REFLECT LOOP. Free things first, every turn: the compiler's words route to the wall
        // table, and only what the table does not recognise costs a model call.
        String lastLog = "";
        for (int turn = 1; turn <= TURNS; turn++) {
            trace.progress(bump, "gate: turn " + turn + " of " + TURNS + " under JDK " + to);
            Runner.Result build = runner.build(to);
            trace.built("gate-build-" + turn, build);
            if (!build.infra()) {
                Runner.Result test = runner.test(to);
                trace.built("gate-test-" + turn, test);
                if (!test.infra() && test.passed()) {
                    gateGreen = true;
                    return "green\nthe gate is green after " + turn + " turn(s); walls cleared: "
                            + walls.appliedSoFar();
                }
                lastLog = test.summary();
            } else {
                lastLog = build.summary();
            }

            Walls.Turn t = walls.match(lastLog, Integer.parseInt(to));
            if (t.fixed()) {
                trace.applied("walls", t.what());
                continue;
            }

            // The residue: a wall the table does not know. This is what a model is FOR.
            if (!fixerTurn(lastLog)) {
                break;
            }
        }

        // The gate established nothing green. The verdict argues what this is; the estimator prices
        // the attempt either way, because the record is the product even when the bump is not.
        String argued = agents.verdict().run(brief(lastLog)
                + "\nThe reflect loop ended without a green gate. Walls cleared: "
                + walls.appliedSoFar());
        price();
        String word = word(argued, "blocked-dependency", "behavior-change", "infra");
        return word + "\n" + argued;
    }

    /** One fixer round: propose, enforce, judge. True to keep looping, false to stop. */
    private boolean fixerTurn(String log) throws IOException {
        String reply = agents.fixer().run(brief(log));
        for (int again = 0; again <= REASK; again++) {
            if (Edits.declined(reply)) {
                trace.progress(bump, "fixer declined: " + reply.lines().findFirst().orElse(""));
                return false;
            }
            Edits.Applied applied = Edits.apply(ws, reply);
            if (applied.count() == 0) {
                if (again == REASK) {
                    trace.progress(bump, "fixer edit unusable: " + applied.report());
                    return false;
                }
                reply = agents.fixer().run(brief(log)
                        + "\nYour previous answer could not be applied: " + applied.report()
                        + "\nAnswer again with corrected EDIT blocks.");
                continue;
            }
            trace.applied("fixer", applied.report());

            String judgement = agents.fixCritic().run("The failing build said:\n" + log
                    + "\n\nThe proposed edit, already applied:\n" + reply);
            String word = word(judgement, "sound", "gaming", "off-target");
            if ("gaming".equals(word)) {
                revert();
                trace.progress(bump, "fix-critic: gaming; edit reverted");
                return false;
            }
            if ("off-target".equals(word) && again < REASK) {
                revert();
                reply = agents.fixer().run(brief(log)
                        + "\nA reviewer judged your edit aims at the wrong wall:\n" + judgement
                        + "\nAnswer again.");
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * THE CONTEXT IS HANDED OVER, NOT FETCHED. The fixer has no tools, so everything it needs to
     * write an exact anchor travels in the brief: the failing log, and the files the log itself
     * names, cut around the lines it names.
     */
    private String brief(String log) throws IOException {
        StringBuilder b = new StringBuilder("Migration: JDK " + from + " -> " + to + " (" + bump
                + ")\nWalls already cleared mechanically: " + walls.appliedSoFar()
                + "\n\nThe failing build:\n" + log + "\n");
        Set<String> named = new LinkedHashSet<>();
        Matcher m = Pattern.compile("/work/([\\w./$-]+\\.(?:java|xml|kts|gradle))[:\\[]").matcher(log);
        while (m.find() && named.size() < 3) {
            named.add(m.group(1));
        }
        if (named.isEmpty()) {
            named.add("pom.xml");
        }
        for (String rel : named) {
            Path f = ws.resolve(rel);
            if (Files.isRegularFile(f)) {
                String content = Files.readString(f);
                if (content.length() > 8000) {
                    content = cutAround(content, log, rel);
                }
                b.append("\nThe file ").append(rel).append(":\n").append(content).append('\n');
            }
        }
        return b.toString();
    }

    /** The region the log points at, when a file is too large to travel whole. */
    private static String cutAround(String content, String log, String rel) {
        Matcher line = Pattern.compile(Pattern.quote(rel) + ":\\[?(\\d+)").matcher(log);
        int at = line.find() ? Integer.parseInt(line.group(1)) : 1;
        List<String> lines = content.lines().toList();
        int lo = Math.max(0, at - 40);
        int hi = Math.min(lines.size(), at + 40);
        return "[lines " + (lo + 1) + "-" + hi + " of " + lines.size() + "]\n"
                + String.join("\n", lines.subList(lo, hi));
    }

    private void revert() {
        try {
            Shell.run(ws, Map.of(), Duration.ofMinutes(2), "git", "checkout", "--", ".");
        } catch (IOException | InterruptedException e) {
            // A revert that failed leaves edits in place; the next build will judge them. Recorded,
            // because a workspace in a state the trace does not explain is the thing we never allow.
            trace.progress(bump, "revert failed: " + e.getMessage());
        }
    }

    private void price() {
        String estimate = agents.estimator().run("The bump " + bump + "; walls cleared: "
                + walls.appliedSoFar() + ". Estimate from this record.");
        Matcher m = Pattern.compile("minutes:\\s*(\\d+)").matcher(estimate);
        trace.priced(bump, m.find() ? m.group(1) : "", estimate);
    }

    /** The first of the allowed words found in the answer; the first option is the default. */
    private static String word(String reply, String... allowed) {
        String lower = reply == null ? "" : reply.toLowerCase();
        int best = Integer.MAX_VALUE;
        String chosen = allowed[0];
        for (String w : allowed) {
            int at = lower.indexOf(w);
            if (at >= 0 && at < best) {
                best = at;
                chosen = w;
            }
        }
        return chosen;
    }
}
