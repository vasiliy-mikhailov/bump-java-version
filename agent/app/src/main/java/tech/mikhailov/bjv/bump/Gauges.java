package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import tech.mikhailov.bjv.jvm.Declared;
import tech.mikhailov.bjv.jvm.Migrate;
import tech.mikhailov.bjv.jvm.Modules;
import tech.mikhailov.bjv.jvm.Runner;


/**
 * WHAT THE PROJECT SAYS ABOUT ITSELF, READ RATHER THAN INFERRED FROM A FAILURE.
 *
 * <p>Three of these read the build files and report: which build system owns each module, every
 * version a module declares and where it declares it, and every JDK level still sitting below the
 * target. The fourth compiles, because whether an edit builds is a fact too and reading a file
 * cannot produce it.
 *
 * <p>They report and they do not advise. Whether a version is high enough, and what to do about a
 * module that is Gradle, are judgements that belong in the prompt next to the floors. The one time
 * a tool here held a verdict of its own it disagreed with the prose beside it, answered that every
 * floor was met, and every Spring project in the corpus kept its Boot version.
 */
final class Gauges {

    private Gauges() {
    }

    /**
     * WHICH BUILD SYSTEM, AS A FACT RATHER THAN AN INFERENCE FROM A FAILURE.
     *
     * <p>IT ANSWERS WHERE A VERSION LIVES, NOT WHETHER A RECIPE CAN RUN. {@code apply_recipe}
     * reaches both build systems since {@link Migrate#rewriteGradle}, so being Gradle is no longer
     * a reason a pin cannot be applied, and three prompts went on saying it was for four hundred
     * bumps after it stopped being true. What the answer is still worth is placement: a version in
     * a Gradle project lives in a plugins block, a buildscript classpath, a property, a dependency
     * string or a version catalog, and which of those is a different question per module.
     *
     * <p>PER MODULE, and both is a real answer. A repository can carry a pom at the root and Gradle
     * modules underneath, and a root-level file check calls that Maven and is wrong about half of
     * it. This reports what each module actually has.
     *
     * <p>It reports and does not advise. What to do about a Gradle module is in the prompt, next to
     * the floors, where the judgement belongs.
     */
    static Map<ToolSpecification, ToolExecutor> buildSystem(Path root) {
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
    static Map<ToolSpecification, ToolExecutor> declaredVersions(Path root) {
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
    static Map<ToolSpecification, ToolExecutor> targets(Path root, String targetJdk) {
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

    /** Try the target-JDK build. Producers only: feedback for them, never evidence for the chain. */
    static Map<ToolSpecification, ToolExecutor> tryBuild(Path root, Runner runner,
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
}
