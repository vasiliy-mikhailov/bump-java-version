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
        tools.putAll(buildSystem(root));
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
    private static Map<ToolSpecification, ToolExecutor> recipe(Migrate migrate, Tree tree,
                                                              String jdk) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("apply_recipe")
                .description("Run an OpenRewrite recipe against this project. Give the whole "
                        + "rewrite.yml as `yaml`. This is how a version gets pinned: hand-editing a "
                        + "pom guesses at placement and guessing wrong is silent, while the recipes "
                        + "know where a version belongs in Maven AND in Gradle. Useful ones: "
                        + "org.openrewrite.maven.UpgradeDependencyVersion, "
                        + "org.openrewrite.maven.UpgradeParentVersion, "
                        + "org.openrewrite.maven.UpgradePluginVersion, "
                        // The one for pinning a version a project does not declare directly, which
                        // was missing from this list. An agent needing it invented
                        // `AddDependencyManagementDependency`, a plausible name that does not
                        // exist, and the run reported success having done nothing.
                        + "org.openrewrite.maven.AddManagedDependency (to pin a version in "
                        + "dependencyManagement, including one arriving transitively), "
                        + "org.openrewrite.maven.ChangePropertyValue (when the version lives in a "
                        + "property a dependency reads), "
                        + "org.openrewrite.gradle.UpgradeDependencyVersion, "
                        + "org.openrewrite.gradle.UpgradeTransitiveDependencyVersion, "
                        + "org.openrewrite.gradle.UpdateGradleWrapper. "
                        // THE FRAMEWORK MIGRATIONS, which were missing entirely. An agent that
                        // needs to move Spring Boot and is shown only version-bumping recipes will
                        // assemble the move by hand out of parent bumps and renames, which is far
                        // more likely to go wrong than one recipe that chains the whole thing.
                        + "For Spring Boot, prefer the migration recipe over bumping the parent by "
                        + "hand: org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7, "
                        + "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5 (which resolves "
                        + "the newest 3.5 patch itself and chains Framework 6, Security 6.5, Cloud "
                        + "2025 and the property renames), and "
                        // Two doers in one sweep read a floor that named 3.5.4 and wrote exactly
                        // 3.5.4 into the parent block. It is a real version and the build was
                        // green, so nothing complained. It is also twelve patch releases behind
                        // its own line, and it manages Tomcat 10.1.43 with eleven CRITICAL+HIGH.
                        // The repo whose doer ran the recipe instead landed on 3.5.16 and went
                        // 63 to 2. The preference stated here is not a style note.
                        + "A literal parent bump stops at the number you type, and the Boot patch "
                        + "releases are where the CVE fixes are, so type a version only when no "
                        + "migration recipe covers the line. "
                        + "org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0. Note the "
                        + "package differs by line: boot2, boot3, boot4. There is no free 4.1 "
                        + "recipe. "
                        // The jakarta rename, which is what a Boot 2->3 move MEANS in source, and
                        // which was being attempted by hand with edit_file.
                        + "Moving from Boot 2 to Boot 3 renames javax to jakarta in source, and "
                        + "there are recipes for that rather than editing imports by hand: "
                        + "org.openrewrite.java.migrate.jakarta.JavaxEEApiToJakarta for the lot, or "
                        + "JavaxMailToJakartaMail and JavaxServletToJakartaServlet for one API. "
                        + "Emit BOTH the maven and the "
                        + "gradle form for each pin: the one that does not match this project is a "
                        + "no-op, so one recipe works either way. USE ONLY NAMES FROM THIS LIST: a "
                        + "recipe that does not exist is skipped by OpenRewrite with a warning and "
                        + "the run still reports success, so a guessed name changes nothing and "
                        + "looks like it worked.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("yaml", """
                                A complete rewrite.yml. The header is fixed and the harness supplies \
                                it if you leave it out, so what matters is the recipeList:

                                type: specs.openrewrite.org/v1beta/recipe
                                name: com.bjv.Bump
                                displayName: what this run is for
                                recipeList:
                                  - org.openrewrite.maven.UpgradeDependencyVersion:
                                      groupId: org.projectlombok
                                      artifactId: lombok
                                      newVersion: 1.18.46

                                `type` is the document KIND and has exactly one legal value, the one \
                                above. It is not the id of the recipe you want; that goes in \
                                recipeList. Every entry there takes its arguments as an indented \
                                map, as shown.""")
                        .required("yaml")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String yaml = Reasoning.field(request.arguments(), "yaml");
            if (!yaml.contains("recipeList")) {
                return "that is not a recipe file: it needs a recipeList. "
                        + "See the tool description for the shape and the recipes worth using.";
            }
            try {
                // WHAT IT DID, NOT WHAT IT SAID. OpenRewrite skips a recipe it cannot load,
                // prints why, adds "Execution will continue regardless" and exits 0 -- and that is
                // only one of its wordings. Measured across this corpus: 87 runs said "Recipe
                // validation errors detected", 80 said a required argument was missing, 13 said the
                // class could not be found, and every one of them came back as rc=0. Matching those
                // strings was a losing game; there is always another.
                //
                // So the tool reports the EFFECT. Whether the working tree moved is one fact that
                // covers every way a recipe can fail to do anything, including ways nobody has seen
                // yet, and it is the same fact the verifier will check afterwards. The plan-do-
                // verify loop is what turns "nothing changed" into another attempt; this only has
                // to stop lying to it.
                String before = tree == null ? "" : tree.diff();
                String said = migrate.apply(yaml, jdk);
                if (tree == null) {
                    return said;
                }
                return reported(before, tree.diff(), said);
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
    /**
     * WHAT THE BUILD FILES SAY, AND NOTHING ABOUT WHETHER IT IS GOOD ENOUGH.
     *
     * <p>This was {@code check_pins}: it took the floor list and answered "ok" or "OUTSTANDING" per
     * pin. To do that it had to be told which artifacts mattered, and it was told by a regex parsing
     * the same prose the agents were reading. The two readers disagreed exactly once and it was
     * enough — the regex looked for {@code org.springframework.boot:spring-boot}, an artifact no
     * application declares, concluded every floor was met, and the phase skipped without showing any
     * agent the instruction it was gating. Every Spring project in the corpus kept its Boot version
     * while the log read "every pin met".
     *
     * <p>So the verdict moved to the planner, which holds the floor list as prose and can see that a
     * parent block is how a Maven project states its Boot line. This reports.
     */
    /**
     * WHICH BUILD SYSTEM, AS A FACT RATHER THAN AN INFERENCE FROM A FAILURE.
     *
     * <p>{@code apply_recipe} runs the OpenRewrite MAVEN plugin. On a project with no pom it
     * answers "no POM in this directory" and no recipe can execute: not that one, not any, not on
     * a retry. Before this tool the only way an agent could learn that was to call the tool, read
     * the error, and infer the project type from it — three inference steps standing in for a
     * question it could simply ask, and in the corpus they usually ended in calling it again.
     *
     * <p>PER MODULE, and both is a real answer. A repository can carry a pom at the root and Gradle
     * modules underneath, and a root-level file check calls that Maven and is wrong about half of
     * it. This reports what each module actually has.
     *
     * <p>It reports and does not advise. What to do about a Gradle module is in the prompt, next to
     * the floors, where the judgement belongs.
     */
    private static Map<ToolSpecification, ToolExecutor> buildSystem(Path root) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("build_system")
                .description("Report, PER MODULE, whether it is built by Maven, by Gradle, or "
                        + "by both. Useful for reading a project, not for choosing a tool: "
                        + "bump_patch, bump_line and apply_recipe all pick their own actuator from "
                        + "what is at the root, so a recipe runs on a Gradle project as it does on "
                        + "a Maven one. A repository can be mixed, so the answer is per module "
                        + "rather than one word for the whole project.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            try {
                List<Modules.Module> modules = Modules.of(root);
                StringBuilder out = new StringBuilder();
                int maven = 0;
                int gradle = 0;
                for (Modules.Module m : modules) {
                    Path dir = root.resolve(m.path()).normalize();
                    boolean hasPom = Files.isRegularFile(dir.resolve("pom.xml"));
                    boolean hasGradle = Files.isRegularFile(dir.resolve("build.gradle"))
                            || Files.isRegularFile(dir.resolve("build.gradle.kts"))
                            || Files.isRegularFile(dir.resolve("settings.gradle"))
                            || Files.isRegularFile(dir.resolve("settings.gradle.kts"));
                    String said = hasPom && hasGradle ? "maven and gradle"
                            : hasPom ? "maven"
                            : hasGradle ? "gradle"
                            : "no build file of its own";
                    if (hasPom) {
                        maven++;
                    } else if (hasGradle) {
                        gradle++;
                    }
                    out.append("  ").append(m.name()).append("  ").append(said).append('\n');
                }
                if (modules.isEmpty()) {
                    return "no modules were found under this workspace";
                }
                String head = gradle == 0 ? "Every module here is Maven."
                        : maven == 0
                                ? "Every module here is Gradle. Recipes run through the Gradle "
                                        + "plugin, from the root, reaching every module at once."
                                : "Mixed. A recipe run reaches both, by running each actuator in "
                                        + "turn; the arms that do not match are no-ops.";
                return head + "\n" + out;
            } catch (IOException e) {
                return "could not read the build files: " + e.getMessage();
            }
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    private static Map<ToolSpecification, ToolExecutor> declaredVersions(Path root) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("declared_versions")
                .description("Read the build files and report, PER MODULE, every version the module "
                        + "itself declares and WHERE it declares it: a parent block, a dependency, a "
                        + "plugin, a property, a Gradle string, the wrapper. A module is described "
                        + "by its own build files only, so a version in one says nothing about a "
                        + "sibling, and a module that declares nothing says so rather than being "
                        + "omitted. Where a version lives decides which recipe can move it. This "
                        + "reports what the project says; deciding whether it is high enough is "
                        + "your job, against the floors you were given.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            try {
                return Declared.report(root, Modules.of(root));
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
    /**
     * WHAT THE RUN DID, PUT WHERE THE AGENT READS FIRST.
     *
     * <p>The exit code is not the answer. OpenRewrite skips a recipe it cannot load, prints why,
     * adds "Execution will continue regardless" and exits 0. Measured across this corpus: 87 runs
     * said "Recipe validation errors detected", 80 said a required argument was missing, 13 said
     * the class could not be found, and every one came back rc=0. The harness used to recognise the
     * third wording and reported the other two as success, which is the same bug with better
     * manners.
     *
     * <p>So this reports the EFFECT instead. Whether the working tree moved covers every way a
     * recipe can fail to do anything, including wordings nobody has met yet, and it needs no
     * knowledge of the plugin's vocabulary. Turning "nothing changed" into another attempt is the
     * plan-do-verify loop's job; this only has to stop telling it the opposite.
     */
    static String reported(String before, String after, String said) {
        return (after.equals(before)
                ? "NOTHING CHANGED IN THE WORKING TREE. The recipe ran and left every file as it "
                        + "was, so whatever it was meant to do has not happened. The exit code says "
                        + "nothing about this: OpenRewrite reports a recipe it could not load, or an "
                        + "argument it needed, and continues anyway.\n\n"
                : "the working tree changed.\n\n")
                + said;
    }

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
    static Map<ToolSpecification, ToolExecutor> raising(Path root, Runner runner, Migrate migrate,
                                                        Tree tree, String targetJdk, String under,
                                                        Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                only(root, Set.of("list_dir", "read_file", "edit_file"));
        tools.putAll(build(root, runner, targetJdk));
        tools.putAll(targets(root, targetJdk));
        tools.putAll(patchBump(root, migrate, tree, under));
        tools.putAll(lineBump(migrate, tree, under));
        tools.putAll(recipe(migrate, tree, under));
        tools.putAll(buildSystem(root));
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

    /** Run a document the harness wrote itself, reporting what it did rather than what it said. */
    private static String ran(Migrate migrate, Tree tree, String jdk, String yaml) {
        try {
            String before = tree == null ? "" : tree.diff();
            String said = migrate.apply(yaml, jdk);
            return tree == null ? said : reported(before, tree.diff(), said);
        } catch (IOException e) {
            return "could not run the recipe: " + e.getMessage();
        }
    }

    /** The line a version sits on: major.minor, or the whole string when it has neither. */
    private static String line(String version) {
        String[] part = version.split("[.-]");
        return part.length >= 2 ? part[0] + "." + part[1] : version;
    }

    /**
     * The document the harness writes so that nobody has to write it.
     *
     * <p>Four arms, because one artifact has four places it can be written and the project decides
     * which: a Maven dependency or a managed one, a Maven parent, a Gradle dependency or buildscript
     * classpath, and a Gradle plugins block. Whichever does not apply is a silent no-op, measured on
     * a real project rather than assumed, so one document is correct on either build system.
     *
     * <p>Every name here was checked against the jars the actuators load, because a name that does
     * not resolve is a run the Maven plugin reports as a success that changed nothing.
     */
    static String bumpYaml(String group, String artifact, String version) {
        return """
                type: specs.openrewrite.org/v1beta/recipe
                name: com.bjv.Bump
                displayName: %s to %s
                recipeList:
                  - org.openrewrite.maven.UpgradeDependencyVersion:
                      groupId: %s
                      artifactId: %s
                      newVersion: %s
                      overrideManagedVersion: true
                  - org.openrewrite.maven.UpgradeParentVersion:
                      groupId: %s
                      artifactId: %s
                      newVersion: %s
                  - org.openrewrite.gradle.UpgradeDependencyVersion:
                      groupId: %s
                      artifactId: %s
                      newVersion: %s
                  - org.openrewrite.gradle.plugins.UpgradePluginVersion:
                      pluginIdPattern: %s
                      newVersion: %s
                """.formatted(artifact, version,
                        group, artifact, version,
                        group, artifact, version,
                        group, artifact, version,
                        group, version);
    }

    /**
     * A NEWER PATCH OF THE LINE THE PROJECT IS ALREADY ON, on either build system.
     *
     * <p>Nearly every floor in this harness is the newest patch of a line the target JDK can run,
     * and that is not an accident of how they were written: a patch release carries the CVE fixes
     * and changes no API, so it is the move that needs no migration and loses no test. Lombok
     * 1.18.30, Tomcat 9.0.105, Boot 2.7.18, Boot 3.5.16 are all that shape.
     *
     * <p>The agent names an artifact and a version. It does not name a recipe, so it cannot invent
     * one; it does not write yaml, so the header cannot be wrong; and it is not asked which build
     * system it is on, because that is the harness's question to answer and answering it wrongly is
     * what cost this corpus 622 calls and every Gradle pin it ever owed.
     */
    private static Map<ToolSpecification, ToolExecutor> patchBump(Path root, Migrate migrate,
                                                                   Tree tree, String jdk) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("bump_patch")
                .description("Move one artifact to a newer patch of the line it is already on: "
                        + "42.7.1 to 42.7.2, Spring Boot 2.7.3 to 2.7.18, lombok 1.18.20 to "
                        + "1.18.30. Give the coordinates and the version you want; the harness "
                        + "writes the recipe and picks the actuator, so this does the same thing on "
                        + "a Maven project and on a Gradle one and you do not have to know which "
                        + "you are on. Patch releases are where the CVE fixes are and they change "
                        + "no API, which is why almost every floor in your brief is one. Crossing a "
                        + "minor or a major is a different move and bump_line is what does it.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("groupId", "org.projectlombok")
                        .addStringProperty("artifactId", "lombok")
                        .addStringProperty("newVersion",
                                "The patch to land on. Same major.minor as what is declared now.")
                        .required("groupId", "artifactId", "newVersion")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String group = Reasoning.field(request.arguments(), "groupId").strip();
            String artifact = Reasoning.field(request.arguments(), "artifactId").strip();
            String version = Reasoning.field(request.arguments(), "newVersion").strip();
            if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) {
                return "bump_patch needs groupId, artifactId and newVersion, all three of them";
            }
            if (!version.matches("\\d[0-9A-Za-z.\\-_]*")) {
                return "\"" + version + "\" does not look like a version";
            }
            List<String> crossing;
            try {
                // The plugin id alone as well as the coordinate, because a Gradle plugins block
                // writes `id "org.springframework.boot" version "2.7.3"` and no artifactId at all.
                crossing = Declared.valuesFor(root, group + ":" + artifact, group).stream()
                        .filter(v -> v.matches("\\d.*"))
                        .filter(v -> !line(v).equals(line(version)))
                        .distinct()
                        .toList();
            } catch (IOException e) {
                return "could not read the build files to check the distance: " + e.getMessage();
            }
            if (!crossing.isEmpty()) {
                return "that is not a patch move, so this is the wrong tool for it. " + artifact
                        + " is declared at " + String.join(", ", crossing) + " and you asked for "
                        + version + ", which is a different line. bump_line runs the migration for "
                        + "the line rather than writing the number, which is the difference between "
                        + "a project that starts and one that compiles and then fails on a property "
                        + "renamed three minors ago.";
            }
            return ran(migrate, tree, jdk, bumpYaml(group, artifact, version));
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * CROSSING A LINE, which is a migration and not a number.
     *
     * <p>Six minor releases of renamed properties and withdrawn APIs sit between a Boot 2.1 project
     * and 2.7, and a version typed into the parent block crosses none of them. Two doers in one
     * sweep read a floor naming 3.5.4 and wrote exactly 3.5.4; the build was green, nothing
     * complained, and the project kept Tomcat 10.1.43 with eleven CRITICAL+HIGH. The repository
     * whose doer ran the recipe instead landed on 3.5.16 and went 63 findings to 2.
     *
     * <p>So this takes the same two coordinates and a target, and derives the recipe. Deriving beats
     * remembering: an agent asked to name one invented AddDependencyManagementDependency, which does
     * not exist, and the Maven plugin reported that as a success.
     */
    private static Map<ToolSpecification, ToolExecutor> lineBump(Migrate migrate, Tree tree,
                                                                  String jdk) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("bump_line")
                .description("Move one artifact to a different major or minor line: Spring Boot "
                        + "2.7 to 3.5, or 2.0 to 2.7. This runs the migration for that line rather "
                        + "than writing a version, because the renamed properties and withdrawn "
                        + "APIs between two lines are the whole cost of the move and a number "
                        + "crosses none of them. The harness picks the recipe from what you name "
                        + "and picks the actuator from the build system, so it works the same on "
                        + "Maven and on Gradle. If nothing here covers the artifact you name it "
                        + "says so rather than doing something else, and apply_recipe is then the "
                        + "way through.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("groupId", "org.springframework.boot")
                        .addStringProperty("artifactId", "spring-boot-starter-parent")
                        .addStringProperty("newVersion",
                                "The line to land on. The patch is resolved by the recipe, so 3.5 "
                                        + "and 3.5.16 mean the same thing here.")
                        .required("groupId", "artifactId", "newVersion")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String group = Reasoning.field(request.arguments(), "groupId").strip();
            String artifact = Reasoning.field(request.arguments(), "artifactId").strip();
            String version = Reasoning.field(request.arguments(), "newVersion").strip();
            if (group.isEmpty() || artifact.isEmpty() || version.isEmpty()) {
                return "bump_line needs groupId, artifactId and newVersion, all three of them";
            }
            String recipe = migrationFor(group, version);
            if (recipe.isEmpty()) {
                return "nothing here migrates " + group + ":" + artifact + " to " + version
                        + ". The lines this can cross on its own are Spring Boot's, 2.0 through "
                        + "2.7, 3.0 through 3.5, and 4.0. For anything else apply_recipe takes a "
                        + "recipe you name, and declared_versions will show you where the version "
                        + "currently lives so you can pick the right one.";
            }
            return ran(migrate, tree, jdk, """
                    type: specs.openrewrite.org/v1beta/recipe
                    name: com.bjv.Bump
                    displayName: %s to the %s line
                    recipeList:
                      - %s
                    """.formatted(artifact, line(version), recipe));
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * The migration that crosses one line, derived rather than remembered.
     *
     * <p>The bounds are read off rewrite-spring 6.31.0 rather than assumed: boot2 carries 2_0
     * through 2_7, boot3 carries 3_0 through 3_5, boot4 carries 4_0. A name outside those is a
     * recipe that does not exist, which the Gradle actuator fails loudly on and the Maven one
     * reports as a run that succeeded and changed nothing.
     */
    static String migrationFor(String group, String version) {
        if (!group.startsWith("org.springframework.boot")) {
            return "";
        }
        String[] part = version.split("[.-]");
        if (part.length < 2) {
            return "";
        }
        int major;
        int minor;
        try {
            major = Integer.parseInt(part[0]);
            minor = Integer.parseInt(part[1]);
        } catch (NumberFormatException notAVersion) {
            return "";
        }
        boolean published = (major == 2 && minor <= 7)
                || (major == 3 && minor <= 5)
                || (major == 4 && minor == 0);
        return published
                ? "org.openrewrite.java.spring.boot" + major + ".UpgradeSpringBoot_" + major + "_"
                        + minor
                : "";
    }

    /** A pin-phase producer: it may run recipes and check what landed, and edit nothing by hand. */
    static Map<ToolSpecification, ToolExecutor> pinning(Path root, Migrate migrate, Tree tree,
                                                        String jdk,
                                                        Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file"));
        // THE NAMED MOVE FIRST, the raw document last. A phase that owes a floor is nearly always
        // making one of two moves, and both of them are now something it can ask for by name.
        tools.putAll(patchBump(root, migrate, tree, jdk));
        tools.putAll(lineBump(migrate, tree, jdk));
        tools.putAll(recipe(migrate, tree, jdk));
        tools.putAll(buildSystem(root));
        tools.putAll(declaredVersions(root));
        tools.putAll(jar());
        tools.putAll(gradle());
        tools.putAll(history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /** A pin-phase critic: it reads and checks, and cannot run a recipe of its own. */
    static Map<ToolSpecification, ToolExecutor> judging(Path root, Tree tree,
                                                        Trace trace,
                                                        String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file"));
        tools.putAll(declaredVersions(root));
        tools.putAll(jar());
        // A JUDGE THAT CANNOT CHECK A CLAIM CAN ONLY TRUST IT OR IGNORE IT, and this one
        // ignored it: a doer reported a pin unreachable because its module is Gradle and
        // apply_recipe runs the Maven plugin, and the verifier, seeing only that the version
        // was below the floor, answered `again` twice to a colleague with no tool to try
        // anything with. Same reason inspect_jar is given to judges.
        //
        // The premise is gone now -- there is a Gradle actuator -- but the tool stays. A verifier
        // that can see which build system a module is on can tell a pin that was not attempted
        // from one that was attempted and did not land, and those want different answers.
        tools.putAll(buildSystem(root));
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
            String dists = Env.get("BJV_GRADLE_DISTS");
            if (dists == null) {
                return "BJV_GRADLE_DISTS is unset; no sealed Gradle distributions are available";
            }
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
