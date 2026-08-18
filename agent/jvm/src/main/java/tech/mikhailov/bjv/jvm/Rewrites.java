package tech.mikhailov.bjv.jvm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

import tech.mikhailov.bjv.engine.Reasoning;

/**
 * MOVING A VERSION, WHICH IS AN OPENREWRITE RUN AND NOT AN EDIT.
 *
 * <p>Three ways in, narrowest first: a newer patch of the line the project is already on, a
 * crossing into another line, and a recipe document the agent writes itself. The first two derive
 * the recipe from two coordinates and a version, so there is nothing for an agent to misremember,
 * and none of the three asks which build system it is on, because that is the harness's question
 * and answering it wrongly is what cost this corpus 622 calls and every Gradle pin it ever owed.
 *
 * <p>All three report the EFFECT rather than the exit code. OpenRewrite skips a recipe it cannot
 * load and still exits 0, so whether the working tree moved is the only answer that covers every
 * way a run can do nothing, including the wordings nobody has met yet.
 */
public final class Rewrites {

    private Rewrites() {
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
    public static Map<ToolSpecification, ToolExecutor> patchBump(Path root, Migrate migrate,
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
    public static Map<ToolSpecification, ToolExecutor> lineBump(Migrate migrate, Tree tree, String jdk) {
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
     * Write and run an OpenRewrite recipe: the one correct way to pin a version.
     *
     * <p>Editing a pom by hand means guessing where the version belongs -- a dependency, a
     * dependencyManagement entry, a property the dependency then reads, a Gradle string, a version
     * catalog -- and guessing wrong is silent. The recipes know, for both build systems, which is
     * also what lets one instruction cover a Maven project and a Gradle one.
     */
    public static Map<ToolSpecification, ToolExecutor> recipe(Migrate migrate, Tree tree, String jdk) {
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
    public static String reported(String before, String after, String said) {
        return (after.equals(before)
                ? "NOTHING CHANGED IN THE WORKING TREE. The recipe ran and left every file as it "
                        + "was, so whatever it was meant to do has not happened. The exit code says "
                        + "nothing about this: OpenRewrite reports a recipe it could not load, or an "
                        + "argument it needed, and continues anyway.\n\n"
                : "the working tree changed.\n\n")
                + said;
    }
}
