package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.deepagents.langchain4j.files.FileToolFactory;
import com.deepagents.langchain4j.files.WorkspaceFileOperations;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * WHAT EACH AGENT CAN REACH, AND NOTHING MORE.
 *
 * <p>Producers get {@code edit_file} and never {@code write_file}: a new file is not a migration
 * step, it is something a critic would then have to catch in prose. Judges read. Everyone gets grep
 * and glob, because a model asking for a tool that does not exist does not degrade, it throws.
 *
 * <p>TEST CODE IS FENCED AT THE EXECUTOR. The prompts say "never edit a test" and a prompt is a
 * suggestion; this wrapper is the rule. An {@code edit_file} whose path sits under a test source
 * root is refused with an answer the model can read, whatever its reasoning said — because a lost
 * test scores zero regardless of anything else the bump achieves.
 *
 * <p>EVERY EXECUTOR IS WRAPPED SO THE TRACE SEES IT WHOLE. The library's flow listener truncates
 * its payloads, which is fine for watching and useless for the corpus: the argument to
 * {@code edit_file} IS the migration step. Recording at the executor catches it before anything
 * shortens it.
 */
final class Tools {

    private Tools() {
    }

    /** Read and look around: what a judge needs to check a claim. */
    static Map<ToolSpecification, ToolExecutor> reading(Path root, Tree tree, Trace trace,
                                                       String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file"));
        tools.putAll(jar());
        tools.putAll(gradle());
        tools.putAll(history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /**
     * What has already been done to this workspace, and what each of those steps changed.
     *
     * <p>Given to everyone. Each stage now commits as it lands, which means a critic is handed only
     * its own producer's diff -- right for judging that producer, and a loss of every bit of context
     * about what came before. Before the commits that context arrived whether it was wanted or not,
     * as one ever-growing diff. This is the same information, asked for rather than dumped.
     */
    private static Map<ToolSpecification, ToolExecutor> history(Tree tree, Trace trace) {
        Map<ToolSpecification, ToolExecutor> two = new LinkedHashMap<>();

        two.put(ToolSpecification.builder()
                .name("what_happened")
                .description("This bump's own event log: every stage, every answer, every objection "
                        + "and every tool result so far, one line each, oldest first. Git shows what "
                        + "LANDED; this shows what was TRIED, including the attempts that were "
                        + "rejected and why. Read it before repeating something. Narrow it with "
                        + "`stage` (survey, baseline, migrate, prepare, bump, gate, troubleshooter) "
                        + "or `agent` when the whole log is too much.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("stage", "optional stage to filter to")
                        .addStringProperty("agent", "optional agent to filter to")
                        .addIntegerProperty("limit", "how many of the most recent events, default 80")
                        .build())
                .build(), (request, memoryId) -> {
                    String stage = field(request.arguments(), "stage");
                    String agent = field(request.arguments(), "agent");
                    int limit = Reasoning.number(request.arguments(), "limit", 60);
                    String log = trace.happened(stage, agent, limit);
                    return log.isBlank() ? "nothing recorded yet for that filter" : log;
                });

        two.put(ToolSpecification.builder()
                .name("history")
                .description("What has already been done to this workspace, oldest first, as "
                        + "<sha>  <what it was>. Stages of this migration appear as `bjv: ...`; "
                        + "anything below them is the project's own history. Use it to find out "
                        + "what an earlier stage did before assuming it did nothing.")
                .parameters(JsonObjectSchema.builder().build())
                .build(), (request, memoryId) -> {
                    String log = tree.log();
                    return log.isBlank() ? "no history could be read" : log;
                });

        two.put(ToolSpecification.builder()
                .name("changed_in")
                .description("The edits one entry from `history` actually made. A label says what a "
                        + "step was called; this says what it did.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("sha", "a commit from history")
                        .required("sha")
                        .build())
                .build(), (request, memoryId) -> {
                    String asked = field(request.arguments(), "sha").strip();
                    String sha = tree.resolve(asked);
                    if (sha.isBlank()) {
                        return "no commit called " + asked + ". Use history for the list.";
                    }
                    String shown = tree.show(sha);
                    return shown.isBlank() ? "nothing readable at " + asked : shown;
                });
        return two;
    }

    /**
     * Read, look around, and move the workspace between landed steps. The outer troubleshoot pair.
     *
     * <p>A campaign is a sequence of steps, so the pair running it needs to see that sequence and to
     * be able to abandon a line that went nowhere. Giving them the rewind as a TOOL rather than as a
     * branch in the harness means the decision to go back is theirs and is argued for in the trace,
     * which is the same reason every other judgement here belongs to an agent.
     *
     * <p>Bounded at {@code floor}, and the bound is the point: a rewind past the campaign's first
     * commit deletes the deterministic migration underneath it. That is precisely the failure that
     * an unscoped revert caused for five bumps, and it must not come back as a tool call.
     */
    static Map<ToolSpecification, ToolExecutor> steering(Path root, Tree tree, String floor,
                                                         Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file"));
        tools.putAll(jar());
        tools.putAll(gradle());
        tools.putAll(history(tree, trace));
        tools.putAll(rewind(tree, floor));
        return recorded(tools, trace, agent);
    }

    private static Map<ToolSpecification, ToolExecutor> rewind(Tree tree, String floor) {
        Map<ToolSpecification, ToolExecutor> two = new LinkedHashMap<>();

        two.put(ToolSpecification.builder()
                .name("steps_so_far")
                .description("The steps this troubleshooting campaign has landed, oldest first, as "
                        + "<sha>  <what it was>. Use it to see what has already been tried before "
                        + "deciding what to do next, and to name a commit for rewind_to.")
                .parameters(JsonObjectSchema.builder().build())
                .build(), (request, memoryId) -> {
                    String log = tree.history(floor);
                    return log.isBlank()
                            ? "no steps have landed yet in this campaign" : log;
                });

        two.put(ToolSpecification.builder()
                .name("rewind_to")
                .description("Put the workspace back to a landed step, discarding everything after "
                        + "it. Use it to abandon a line of edits that led nowhere, so the next "
                        + "attempt starts from a known state instead of on top of the wreckage. "
                        + "Only commits from steps_so_far are reachable; the migration underneath "
                        + "this campaign cannot be undone.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("sha", "a commit from steps_so_far")
                        .required("sha")
                        .build())
                .build(), (request, memoryId) -> {
                    String asked = field(request.arguments(), "sha").strip();
                    String sha = tree.resolve(asked);
                    if (sha.isBlank()) {
                        return "no commit called " + asked + ". Use steps_so_far for the list.";
                    }
                    if (!tree.isAtOrAfter(sha, floor)) {
                        return "REFUSED: " + asked + " is older than this campaign. Rewinding there "
                                + "would delete the migration this troubleshooting sits on top of, "
                                + "which is not yours to undo. The earliest you may go back to is "
                                + "the first entry in steps_so_far.";
                    }
                    tree.revertTo(sha);
                    return "workspace is back at " + asked + ". Everything after it is gone.\n"
                            + tree.history(floor);
                });
        return two;
    }

    /** Read, look around, and edit EXISTING files — outside the test tree. Producers only. */
    static Map<ToolSpecification, ToolExecutor> patching(Path root, Runner runner, Tree tree,
                                                         String targetJdk, Trace trace,
                                                         String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                only(root, Set.of("list_dir", "read_file", "edit_file"));
        tools.putAll(build(root, runner, targetJdk));
        tools.putAll(jar());
        tools.putAll(gradle());
        tools.putAll(history(tree, trace));
        return recorded(guarded(tools), trace, agent);
    }

    /** The one boundary that is enforced rather than requested. */
    static boolean forbidden(String path) {
        return path.contains("src/test/") || path.contains("src/it/")
                || path.contains("src/integrationTest/") || path.contains("..");
    }

    private static Map<ToolSpecification, ToolExecutor> guarded(
            Map<ToolSpecification, ToolExecutor> tools) {
        Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
        tools.forEach((spec, executor) -> wrapped.put(spec, (request, memoryId) -> {
            if ("edit_file".equals(spec.name())) {
                String path = field(request.arguments(), "path");
                if (forbidden(path)) {
                    return "REFUSED: " + path + " is test code, which may never be edited. A lost "
                            + "test scores zero regardless of anything else this bump achieves.";
                }
            }
            return executor.execute(request, memoryId);
        }));
        return wrapped;
    }

    /** Try the target-JDK build. Producers only: feedback for them, never evidence for the chain. */
    private static Map<ToolSpecification, ToolExecutor> build(Path root, Runner runner,
                                                              String targetJdk) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("try_build")
                .description("Compile the project under the TARGET jdk and return what the build "
                        + "said. Use it to check your own edit before answering. The gate that "
                        + "decides the bump runs elsewhere; this is for your benefit only.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            // THE SAME STALE-CLASS TRAP THE GATE HAD, and this one is worse: the troubleshooter is
            // told to check its edit here before answering, and 57 of the 62 calls that answered
            // COMPILED had in fact compiled nothing — Maven found the old classes newer than the
            // sources and skipped. A pom-only edit, which is the commonest edit made here, could
            // never be falsified before the agent committed to it.
            runner.clearClasses();
            Runner.Result r = runner.build(targetJdk);
            String target;
            try {
                int eff = Gate.effectiveTarget(root);
                target = eff < 0 ? "no inspectable main classes" : String.valueOf(eff);
            } catch (IOException unreadable) {
                target = "could not be read";
            }
            // The number the gate will judge on, said plainly, so a producer can tell a compile
            // that ran from one that was skipped and a raised pom from a raised target.
            return (r.infra() ? "DID NOT COMPILE" : "COMPILED")
                    + "\neffective bytecode target after this build: " + target
                    + " (the gate requires " + targetJdk + ")\n" + r.summary();
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * Write and run an OpenRewrite recipe: the one correct way to pin a version.
     *
     * <p>Editing a pom by hand means guessing where the version belongs -- a dependency, a
     * dependencyManagement entry, a property the dependency then reads, a Gradle string, a version
     * catalog -- and guessing wrong is silent. The recipes know, for both build systems, which is
     * also what lets one instruction cover a Maven project and a Gradle one.
     */
    private static Map<ToolSpecification, ToolExecutor> recipe(Migrate migrate, String jdk) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("apply_recipe")
                .description("Run an OpenRewrite recipe against this project. Give the whole "
                        + "rewrite.yml as `yaml`. This is how a version gets pinned: hand-editing a "
                        + "pom guesses at placement and guessing wrong is silent, while the recipes "
                        + "know where a version belongs in Maven AND in Gradle. Useful ones: "
                        + "org.openrewrite.maven.UpgradeDependencyVersion, "
                        + "org.openrewrite.maven.UpgradeParentVersion, "
                        + "org.openrewrite.maven.UpgradePluginVersion, "
                        + "org.openrewrite.gradle.UpgradeDependencyVersion, "
                        + "org.openrewrite.gradle.UpgradeTransitiveDependencyVersion, "
                        + "org.openrewrite.gradle.UpdateGradleWrapper. Emit BOTH the maven and the "
                        + "gradle form for each pin: the one that does not match this project is a "
                        + "no-op, so one recipe works either way.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("yaml", "a complete rewrite.yml, including "
                                + "'type:', 'name:' and 'recipeList:'")
                        .required("yaml")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String yaml = Reasoning.field(request.arguments(), "yaml");
            if (!yaml.contains("recipeList")) {
                return "that is not a recipe file: it needs type, name and recipeList. "
                        + "See the tool description for the recipes worth using.";
            }
            try {
                return migrate.apply(yaml, jdk);
            } catch (IOException e) {
                return "could not run the recipe: " + e.getMessage();
            }
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * What the build files currently say about each pin this phase owes.
     *
     * <p>A critic that reads a diff is judging a claim; this reads the project. Two failures on
     * record made it necessary: a preparer answering NOTHING-TO-DO while its own stage recorded
     * edits, and a troubleshooter reporting a fix it had already reverted.
     */
    private static Map<ToolSpecification, ToolExecutor> pins(Path root, List<Floors.Floor> owed) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("check_pins")
                .description("Read the build files and report, for each version this phase is "
                        + "responsible for, what the project currently declares and whether it "
                        + "meets the floor. This is the fact; what anyone said they did is not.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            try {
                List<Pins.State> states = Pins.check(root, owed);
                StringBuilder out = new StringBuilder();
                for (Pins.State st : states) {
                    out.append(st.satisfied() ? "  ok       " : "  OUTSTANDING  ")
                            .append(st.describe()).append('\n');
                }
                long left = states.stream().filter(st -> !st.satisfied()).count();
                out.append(left == 0 ? "\nevery pin in this phase is at or above its floor"
                        : "\n" + left + " still outstanding");
                return out.toString();
            } catch (IOException e) {
                return "could not read the build files: " + e.getMessage();
            }
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * Which declared pins are still below the target: the bumper's own fact to check.
     *
     * <p>The bumper had this already, as text pasted into a brief, which meant it could be read
     * once and never re-checked after an edit. As a tool both it and its critic can call it after
     * each attempt, which is what turns one re-ask into a loop that ends on the files.
     */
    private static Map<ToolSpecification, ToolExecutor> targets(Path root, String targetJdk) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("check_target")
                .description("Read every build file and report the source, target, release, "
                        + "sourceCompatibility, jvmTarget and toolchain declarations still BELOW "
                        + "the target JDK, with file and line. This is what the gate measures the "
                        + "bump by, so it is the fact -- not the diff, which shows what changed and "
                        + "not what is left.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            try {
                List<String> left = Pins.belowTarget(root, Integer.parseInt(targetJdk));
                return left.isEmpty()
                        ? "nothing is declared below " + targetJdk + " any more"
                        : left.size() + " still below " + targetJdk + ":\n  "
                                + String.join("\n  ", left);
            } catch (IOException | NumberFormatException e) {
                return "could not read the build files: " + e.getMessage();
            }
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /** The bumper: it edits build files by hand, and can see what it has left to do. */
    static Map<ToolSpecification, ToolExecutor> raising(Path root, Runner runner, Tree tree,
                                                        String targetJdk, Trace trace,
                                                        String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                only(root, Set.of("list_dir", "read_file", "edit_file"));
        tools.putAll(build(root, runner, targetJdk));
        tools.putAll(targets(root, targetJdk));
        tools.putAll(history(tree, trace));
        return recorded(guarded(tools), trace, agent);
    }

    /** Its critic: the same fact, and no way to edit. */
    static Map<ToolSpecification, ToolExecutor> checking(Path root, Tree tree, String targetJdk,
                                                          Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file"));
        tools.putAll(targets(root, targetJdk));
        tools.putAll(history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /** A pin-phase producer: it may run recipes and check what landed, and edit nothing by hand. */
    static Map<ToolSpecification, ToolExecutor> pinning(Path root, Migrate migrate, Tree tree,
                                                        String jdk, List<Floors.Floor> owed,
                                                        Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file"));
        tools.putAll(recipe(migrate, jdk));
        tools.putAll(pins(root, owed));
        tools.putAll(jar());
        tools.putAll(gradle());
        tools.putAll(history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /** A pin-phase critic: it reads and checks, and cannot run a recipe of its own. */
    static Map<ToolSpecification, ToolExecutor> judging(Path root, Tree tree,
                                                        List<Floors.Floor> owed, Trace trace,
                                                        String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file"));
        tools.putAll(pins(root, owed));
        tools.putAll(jar());
        tools.putAll(history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /**
     * Which Gradle distributions can actually be used here.
     *
     * <p>A troubleshooter raised a wrapper from 8.15 to 8.16. Neither exists: Gradle went 8.14.3 to
     * 9.0, and it was incrementing a number and hoping. The build then spent its patience trying to
     * download a zip that has never been published. This is the same gap inspect_jar closed for
     * Maven coordinates -- no way to ask what is real -- and the answer is narrower here, because
     * the builds run sealed: the distributions staged on this host are not merely the ones that
     * exist, they are the only ones obtainable.
     */
    private static Map<ToolSpecification, ToolExecutor> gradle() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("gradle_versions")
                .description("The Gradle distributions available to builds here, oldest first. "
                        + "Check this before editing distributionUrl in gradle-wrapper.properties: "
                        + "the builds are sealed, so a version missing from this list cannot be "
                        + "downloaded and the build will fail trying. Gradle version numbers are "
                        + "not contiguous, so do not assume the next one up exists.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String dists = System.getenv().getOrDefault("BJV_GRADLE_DISTS",
                    "/home/vmihaylov/.gradle-dists");
            Path root = Path.of(dists);
            if (!Files.isDirectory(root)) {
                return "the distribution cache is not readable from here";
            }
            List<String> usable = new ArrayList<>();
            try (var entries = Files.list(root)) {
                for (Path dir : entries.filter(Files::isDirectory).toList()) {
                    String name = dir.getFileName().toString();
                    if (!name.startsWith("gradle-")) {
                        continue;
                    }
                    // A half-downloaded distribution has no .ok marker and is not usable: offering
                    // it would send a build to the same download that failed to finish before.
                    boolean complete;
                    try (var deep = Files.walk(dir, 3)) {
                        complete = deep.anyMatch(f -> f.getFileName().toString().endsWith(".ok"));
                    } catch (IOException unreadable) {
                        complete = false;
                    }
                    if (complete) {
                        usable.add(name.substring("gradle-".length()));
                    }
                }
            } catch (IOException e) {
                return "could not read the distribution cache: " + e.getMessage();
            }
            if (usable.isEmpty()) {
                return "no complete Gradle distribution is staged here";
            }
            usable.sort((a, b) -> Migrate.compare(a.replaceAll("-(bin|all)$", ""),
                    b.replaceAll("-(bin|all)$", "")));
            return "Gradle distributions usable in a sealed build, oldest first:\n  "
                    + String.join("\n  ", usable)
                    + "\n\nAnything not on this list cannot be downloaded and will fail the build.";
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * Look inside a dependency, which is the one question the workspace cannot answer.
     *
     * <p>Given to producers and judges alike. A judge that cannot open the jar cannot check a claim
     * about what is in it, and the claims worth checking in a Boot 2 to 3 migration are all of that
     * shape.
     */
    private static Map<ToolSpecification, ToolExecutor> jar() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("inspect_jar")
                .description("Look inside a DEPENDENCY jar already resolved into the local Maven "
                        + "repository. Answers what the project's own files cannot: whether an "
                        + "artifact is compiled against javax or jakarta, how it registers with "
                        + "Spring (spring.factories is Boot 2 only and Boot 3 ignores it), which "
                        + "versions are present, what types it holds, and whether a given type is a "
                        + "class or an interface. Pass `type` to see one type's members.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("artifact",
                                "groupId:artifactId, optionally :version, e.g. "
                                        + "com.baomidou:kaptcha-spring-boot-starter:1.1.0")
                        .addStringProperty("type",
                                "optional fully-qualified type to describe, e.g. "
                                        + "com.baomidou.kaptcha.Kaptcha")
                        .required("artifact")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String artifact = field(request.arguments(), "artifact");
            String type = field(request.arguments(), "type");
            String[] parts = artifact.split(":");
            if (parts.length < 2) {
                return "artifact must be groupId:artifactId or groupId:artifactId:version, got: "
                        + artifact;
            }
            String version = parts.length > 2 ? parts[2] : null;
            try {
                Jars jars = Jars.local();
                return type.isBlank() ? jars.describe(parts[0], parts[1], version)
                        : jars.describeType(parts[0], parts[1], version, type);
            } catch (Jars.NotFound absent) {
                return absent.getMessage();
            }
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * What one tool call may add to the conversation.
     *
     * <p>The context grows MONOTONICALLY across an agent's tool calls and every call re-prefills all
     * of it, so an unbounded read is not paid once, it is paid again on every later call. A
     * generated pom or a vendored file is exactly the read that does this. The trace still records
     * the whole result; only what travels back into the prompt is bounded.
     */
    private static final int MAX_TOOL_RESULT = 20_000;

    private static Map<ToolSpecification, ToolExecutor> recorded(
            Map<ToolSpecification, ToolExecutor> tools, Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
        tools.forEach((spec, executor) -> wrapped.put(spec, (request, memoryId) -> {
            try {
                String result = executor.execute(request, memoryId);
                // Recorded whole, returned bounded: the corpus wants everything, the prompt does not.
                trace.tool(agent, spec.name(), request.arguments(), result);
                if (result != null && result.length() > MAX_TOOL_RESULT) {
                    return result.substring(0, MAX_TOOL_RESULT) + "\n[truncated: " + result.length()
                            + " chars total. Narrow the request if you need the rest.]";
                }
                return result;
            } catch (RuntimeException e) {
                trace.tool(agent, spec.name(), request.arguments(),
                        "threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }));
        return wrapped;
    }

    /** The built-ins, filtered, plus grep and glob. Fails loudly if an upstream rename strips one. */
    private static Map<ToolSpecification, ToolExecutor> only(Path root, Set<String> names) {
        Map<ToolSpecification, ToolExecutor> kept = new LinkedHashMap<>();
        FileToolFactory.build(new WorkspaceFileOperations(root))
                .forEach((spec, executor) -> {
                    if (names.contains(spec.name())) {
                        kept.put(spec, executor);
                    }
                });
        kept.putAll(grep(root));
        kept.putAll(glob(root));
        if (kept.size() != names.size() + 2) {
            throw new IllegalStateException(
                    "expected " + names + " plus grep and glob but got " + kept.keySet());
        }
        return kept;
    }

    private static Map<ToolSpecification, ToolExecutor> grep(Path root) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("grep")
                .description("Search file CONTENTS for a literal string or regular expression, "
                        + "optionally filtered by filename. Returns file:line pairs. To find files "
                        + "by NAME, use glob.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("pattern", "a literal string or Java regular expression")
                        .addStringProperty("glob", "optional filename filter, e.g. *.xml")
                        .required("pattern")
                        .build())
                .build();
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, (request, memoryId) -> search(root, request.arguments()));
        return one;
    }

    private static Map<ToolSpecification, ToolExecutor> glob(Path root) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("glob")
                .description("Find files by PATH pattern, e.g. **/pom.xml, **/*.gradle. Returns "
                        + "matching paths. Use grep to search contents.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("pattern", "a path glob")
                        .required("pattern")
                        .build())
                .build();
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, (request, memoryId) -> matching(root, field(request.arguments(), "pattern")));
        return one;
    }

    private static String matching(Path root, String pattern) {
        if (pattern.isBlank()) {
            return "no pattern given";
        }
        java.nio.file.PathMatcher matcher;
        try {
            matcher = root.getFileSystem().getPathMatcher("glob:"
                    + (pattern.startsWith("**") || pattern.startsWith("/") ? pattern
                    : "**/" + pattern));
        } catch (IllegalArgumentException badPattern) {
            return "not a glob: " + badPattern.getMessage();
        }
        StringBuilder hits = new StringBuilder();
        int found = 0;
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String path = f.toString();
                // Build output is skipped unless the pattern asks for it by name. 52 globs aimed
                // at target/ or *.class returned "no files match", 52 of 52 — and every one was an
                // agent trying to read the class-file major, which is the single thing the gate
                // measures. Hiding it made the gate's own evidence unreachable.
                boolean wantsOutput = pattern.contains("target/") || pattern.contains("build/")
                        || pattern.endsWith(".class");
                if (path.contains("/.git/") || path.contains("/node_modules/")
                        || (!wantsOutput && path.contains("/target/"))) {
                    continue;
                }
                if (matcher.matches(root.relativize(f)) || matcher.matches(f)) {
                    hits.append(root.relativize(f)).append('\n');
                    if (++found >= 200) {
                        hits.append("more suppressed; narrow the pattern\n");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            return "glob failed: " + e.getMessage();
        }
        return found == 0 ? "no files match " + pattern : hits.toString();
    }

    private static String search(Path root, String argumentsJson) {
        String pattern = field(argumentsJson, "pattern");
        String glob = field(argumentsJson, "glob");
        if (pattern.isBlank()) {
            return "no pattern given";
        }
        Pattern re;
        try {
            re = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            re = Pattern.compile(Pattern.quote(pattern));
        }
        StringBuilder hits = new StringBuilder();
        int found = 0;
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (found >= 60) {
                    hits.append("more matches suppressed; narrow the pattern\n");
                    break;
                }
                // MATCH THE FILTER AGAINST WHAT IT LOOKS LIKE. Tested against the bare filename,
                // a path-shaped filter such as **/pom.xml became a regex containing a slash, which
                // no filename can ever contain: 338 greps in the live sweep used one and 338
                // returned "no matches". Agents write path globs because every other tool here
                // takes one, so the filter accepts both and says which it used.
                String name = f.getFileName().toString();
                String rel = root.relativize(f).toString();
                if (!glob.isBlank()) {
                    String globRe = glob.replace(".", "\\.").replace("**/", "(.*/)?")
                            .replace("*", "[^/]*");
                    if (!name.matches(globRe) && !rel.matches(globRe)
                            && !rel.matches(".*/" + globRe)) {
                        continue;
                    }
                }
                String path = f.toString();
                if (path.contains("/.git/") || path.contains("/target/")
                        || path.contains("/node_modules/")) {
                    continue;
                }
                try {
                    int line = 0;
                    for (String text : Files.readAllLines(f)) {
                        line++;
                        if (re.matcher(text).find()) {
                            hits.append(root.relativize(f)).append(':').append(line).append(": ")
                                    .append(text.strip()).append('\n');
                            if (++found >= 60) {
                                break;
                            }
                        }
                    }
                } catch (IOException | java.io.UncheckedIOException binary) {
                    // A file that is not text is not a match.
                }
            }
        } catch (IOException e) {
            return "search failed: " + e.getMessage();
        }
        return found == 0 ? "no matches" : hits.toString();
    }

    /**
     * A tool argument, UNESCAPED.
     *
     * <p>It used to substring the raw JSON, so a model sending {@code java\.version} handed the
     * regex engine {@code java\\.version} — backslash-followed-by-any-char, which is valid, so the
     * PatternSyntaxException fallback never fired and the search silently matched nothing. 250 of
     * 850 greps sent an escaped pattern and 233 returned "no matches"; for 57% of those the
     * correctly unescaped pattern matches text the same agent had already read. It also truncated
     * any argument containing an escaped quote.
     */
    private static String field(String json, String key) {
        return Reasoning.field(json, key);
    }
}
