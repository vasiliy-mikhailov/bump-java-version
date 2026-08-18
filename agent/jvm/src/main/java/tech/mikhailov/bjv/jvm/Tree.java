package tech.mikhailov.bjv.jvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import tech.mikhailov.bjv.engine.Shell;

/**
 * THE WORKSPACE AS A THING THAT CAN BE ROLLED BACK TO A NAMED POINT.
 *
 * <p>A chain of producers and critics needs three different scopes for the word "undo": this step,
 * this campaign, and never. It had one. Nothing committed, so reverting meant {@code git checkout
 * -- .} against the original clone, and a single critic objection deleted the OpenRewrite rewrite,
 * the parent bump, the target propagation and every floor that had landed before it. Five bumps hit
 * that, and three of them settled blocked-dependency arguing about work that no longer existed.
 *
 * <p>Stages commit as they land. Uncommitted therefore means "the step being judged right now", a
 * named commit means "where this campaign began", and anything older is out of reach of any critic.
 * The commits are bookkeeping and never output: the scorer reads the working tree and the corpus
 * reads the trace.
 */
public final class Tree {

    private final Path ws;
    private final Consumer<String> notes;

    public Tree(Path ws, Consumer<String> notes) {
        this.ws = ws;
        this.notes = notes;
    }

    private void note(String message) {
        notes.accept(message);
    }

    /** Uncommitted work: what the step under judgement actually did. */
    public String diff() {
        try {
            Shell.Output stat = git("diff", "--stat=200", "--", ".");
            Shell.Output full = git("diff", "-U2", "--", ".");
            if (!stat.ok() || !full.ok()) {
                note("git diff failed: " + Runner.tail(stat.text()));
                return "";
            }
            return trimmed(stat.text(), full.text()) + untracked();
        } catch (IOException | InterruptedException e) {
            note("git diff failed: " + e.getMessage());
            return "";
        }
    }

    /**
     * Files that did not exist before, which git diff will not show and a critic must still see.
     *
     * <p>A whole class of correct migration is a new file: a jakarta replacement for one class of an
     * abandoned artifact, a configuration that re-registers an autoconfiguration Boot 3 stopped
     * reading. Left out, such a step reaches the workspace and reads back as an empty diff, which
     * the chain scores as "no edit was made" and the critic judges as nothing at all.
     */
    private String untracked() throws IOException, InterruptedException {
        Shell.Output status = git("status", "--porcelain", "--untracked-files=all");
        if (!status.ok()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : status.text().split("\n")) {
            if (!line.startsWith("?? ")) {
                continue;
            }
            String path = line.substring(3).trim();
            out.append("\n--- new file: ").append(path).append('\n');
            try {
                String body = Files.readString(ws.resolve(path));
                out.append(body.length() > 4000
                        ? body.substring(0, 4000) + "\n[new file truncated]\n" : body);
            } catch (IOException | RuntimeException unreadable) {
                out.append("[unreadable]\n");
            }
        }
        return out.toString();
    }

    private static String trimmed(String stat, String body) {
        return stat + (body.length() > 20000 ? body.substring(0, 20000) + "\n[diff truncated]" : body);
    }

    /**
     * Undo the CURRENT step, and nothing earlier.
     *
     * <p>THIS USED TO THROW THE WHOLE MIGRATION AWAY. Nothing in the chain committed, so
     * {@code git checkout -- .} meant "back to the original clone": one critic objection destroyed
     * the OpenRewrite rewrite, the parent bump, the target propagation and every floor, and the
     * verdict was then argued about a workspace that no longer held any of it. Measured on five
     * bumps, three of which settled blocked-dependency describing work that had been deleted.
     *
     * <p>Now each stage commits when it lands, so uncommitted means "this step" and that is the only
     * thing an objection can cost. Untracked files go too, because a step's edit may have been a
     * new file; build output survives, being excluded rather than ignored-by-luck.
     */
    public void revert() {
        try {
            Shell.Output out = git("checkout", "--", ".");
            if (!out.ok()) {
                note("revert failed: " + Runner.tail(out.text()));
            }
            git("clean", "-fd");
        } catch (IOException | InterruptedException e) {
            note("revert failed: " + e.getMessage());
        }
    }

    /** Undo everything back to a named point: the loop critic's power, and only its. */
    public void revertTo(String ref) {
        try {
            Shell.Output out = git("reset", "--hard", ref);
            if (!out.ok()) {
                note("reset failed: " + Runner.tail(out.text()));
            }
            git("clean", "-fd");
        } catch (IOException | InterruptedException e) {
            note("reset failed: " + e.getMessage());
        }
    }

    /**
     * Land what a stage did, so no later objection can reach back past it.
     *
     * <p>The commits are bookkeeping, never output: the scorer reads the working tree and the
     * corpus reads the trace. What they buy is a scope for the word "revert".
     */
    public void land(String stage) {
        try {
            git("add", "-A");
            Shell.Output out = git("-c", "user.email=bjv@localhost", "-c", "user.name=bjv",
                    "commit", "-q", "--allow-empty", "-m", "bjv: " + stage);
            if (!out.ok()) {
                note("commit failed after " + stage + ": "
                        + Runner.tail(out.text()));
            }
        } catch (IOException | InterruptedException e) {
            note("commit failed after " + stage + ": " + e.getMessage());
        }
    }

    /** Where the tree stands now, so a caller can come back to it. */
    public String head() {
        try {
            Shell.Output out = git("rev-parse", "HEAD");
            return out.ok() ? out.text().strip() : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /** Everything done since a named point, which is what judges a campaign rather than a step. */
    public String diffSince(String ref) {
        if (ref.isBlank()) {
            return diff();
        }
        try {
            Shell.Output stat = git("diff", "--stat=200", ref, "--", ".");
            Shell.Output full = git("diff", "-U2", ref, "--", ".");
            if (!stat.ok() || !full.ok()) {
                return diff();
            }
            return trimmed(stat.text(), full.text()) + untracked();
        } catch (IOException | InterruptedException e) {
            return diff();
        }
    }

    /**
     * Keep build output out of the commits without touching a file the project owns.
     *
     * <p>info/exclude is the repository's own private ignore list: nothing appears in a diff the
     * corpus reads, and `git clean` leaves excluded paths alone, so a revert cannot delete a
     * half-built target tree and make the next build look like a fresh checkout.
     */
    public void excludeBuildOutput() {
        try {
            Path exclude = ws.resolve(".git/info/exclude");
            if (Files.isDirectory(exclude.getParent())) {
                Files.writeString(exclude, String.join("\n",
                        "target/", "build/", ".gradle/", "rewrite.yml", Migrate.INIT,
                        "*.class", ""),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            note("could not write .git/info/exclude: " + e.getMessage());
        }
    }

    /**
     * Everything this harness has landed, oldest first, with the project's own HEAD beneath it.
     *
     * <p>Committing the stages made this readable for the first time and it should not be a
     * privilege of the loop that happens to own the commits: a critic judging the bump has every
     * reason to ask what prepare did, and before this it simply could not.
     */
    public String log() {
        try {
            Shell.Output out = git("log", "--reverse", "--format=%h  %s", "-30");
            return out.ok() ? out.text() : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /** What one landed step actually changed, which a one-line label cannot say. */
    public String show(String sha) {
        try {
            Shell.Output stat = git("show", "--stat=200", "--format=%h  %s", sha);
            Shell.Output full = git("show", "-U2", "--format=", sha);
            if (!stat.ok()) {
                return "";
            }
            return trimmed(stat.text(), full.ok() ? full.text() : "");
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /**
     * The landed steps since a floor, newest last, as {@code <sha>  <label>}.
     *
     * <p>What a loop needs to reason about its own campaign: which steps stuck, in what order, and
     * where it could go back to. Bounded at the floor because everything older belongs to a stage
     * this loop is not reviewing.
     */
    public String history(String floor) {
        try {
            Shell.Output out = git("log", "--reverse", "--format=%h  %s",
                    floor.isBlank() ? "-20" : floor + "..HEAD");
            return out.ok() ? out.text() : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    /**
     * Whether {@code sha} sits at or after {@code floor}, which is what makes a rewind safe.
     *
     * <p>THE FLOOR IS NOT ADVISORY. A rewind past it deletes the deterministic migration, and that
     * is not a thing any agent should be able to do by naming the wrong commit: it is the exact
     * failure this whole mechanism exists to prevent, re-introduced through the front door.
     */
    public boolean isAtOrAfter(String sha, String floor) {
        if (floor.isBlank()) {
            return true;
        }
        try {
            return git("merge-base", "--is-ancestor", floor, sha).ok();
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Whether a name resolves to a commit at all, so a typo answers instead of throwing. */
    public String resolve(String sha) {
        try {
            Shell.Output out = git("rev-parse", "--verify", sha + "^{commit}");
            return out.ok() ? out.text().strip() : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    private Shell.Output git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "safe.directory=" + ws));
        cmd.addAll(List.of(args));
        return Shell.run(ws, Map.of(), Duration.ofMinutes(3), cmd.toArray(new String[0]));
    }
}
