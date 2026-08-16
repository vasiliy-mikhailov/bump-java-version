package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE DETERMINISTIC MIGRATION — what runs before any agent is asked anything.
 *
 * <p>Everything here is structure-gated and free: an OpenRewrite program assembled from what the
 * project actually is, the measured version floors, and a target sweep over every pom. It exists
 * ahead of the agents because a step that can be decided by looking is not worth a model call, and
 * because a model that is handed the residue argues about a smaller problem.
 *
 * <p>THE SPRING LINE IS READ FROM THE PROJECT, AND OVERRULED ONLY WHERE THE PROJECT'S LINE CANNOT
 * RUN THE TARGET. Selecting the 3.x migration merely because the target was modern once sent a Boot
 * 2.0 project through the 2-to-3 jump on an 11-to-17 hop: 1297 files rewritten and 1916 of 2409
 * tests lost. That lesson stands wherever the project's own line is still viable, which is why
 * targets through 17 keep the 2.7 ceiling. It stops applying at 21, where Boot 2.x cannot run the
 * target at all and the quiet alternative is shipping a project that will not start. The rule is
 * therefore: the line a project is on decides, until that line cannot run the target, and then the
 * gate decides.
 *
 * <p>The distance matters and is recorded rather than assumed. A 2.7 project is one minor plus the
 * jakarta rename from 3.x; a 2.0 project is seven. Both are attempted, both are traced with the
 * version they started from, so the corpus can answer which distances survive instead of this
 * comment guessing.
 */
final class Migrate {

    /** The recipe versions the corpus measured against; pinned so a run is reproducible. */
    private static final String REWRITE_PLUGIN = "org.openrewrite.maven:rewrite-maven-plugin:6.40.0";

    /**
     * THE GRADLE ACTUATOR'S PLUGIN, PAIRED BY ENGINE RATHER THAN BY DATE.
     *
     * <p>7.33.0 is not the newest published; it is the one whose rewrite-bom is 8.83.0, which is
     * exactly what rewrite-maven-plugin 6.40.0 pins. So both actuators run one recipe engine and a
     * verdict from a Gradle repository is comparable with one from a Maven repository rather than
     * being a measurement of two different programs. 7.39.0 would be 8.89.0.
     */
    private static final String REWRITE_GRADLE_PLUGIN = "org.openrewrite:plugin:7.33.0";

    /**
     * The init script, written beside the recipe rather than into the Gradle home.
     *
     * <p>jvm-run will mount a file at /root/.gradle/init.gradle, and anything there applies to
     * EVERY Gradle invocation in the container, which includes the baseline build and test the
     * conservation gate compares against. A script passed with --init-script applies to the one
     * invocation that asked for it.
     */
    static final String INIT = "bjv-rewrite.init.gradle";
    private static final String RECIPE_JARS = "org.openrewrite.recipe:rewrite-migrate-java:3.36.0,"
            + "org.openrewrite.recipe:rewrite-spring:6.31.0";

    /** The LTS ladder, so a hop can be asked which rungs it crosses. */
    private static final int[] LTS = {8, 11, 17, 21, 25};

    private final Path ws;
    private final String hoptools;
    private final Trace trace;

    Migrate(Path ws, String hoptools, Trace trace) {
        this.ws = ws;
        this.hoptools = hoptools;
        this.trace = trace;
    }

    /**
     * The Tomcat floor for the 17->21 hop.
     *
     * <p>Measured, not chosen: this corpus scores tomcat-embed-core 9.0.65 at 22 CRITICAL+HIGH,
     * 9.0.83 at 19 and 9.0.105 at 14. Nothing in a Java bump ever looked at that. Tomcat moved
     * because SPRING moved -- Boot 2.7.18 pins tomcat.version 9.0.83 -- so the project inherited
     * whichever Tomcat its framework happened to carry, and 9.0.83 is the last patch of Boot 2.7's
     * line rather than of Tomcat's.
     */
    private static final String TOMCAT_9 = "9.0.105";

    /**
     * The Boot line a target JDK can actually run, and the version to land on.
     *
     * <p>SPRING BOOT 2.7 DOES NOT SUPPORT JAVA 21. It was the last 2.x line, it went end-of-life in
     * 2023, and a 17->21 hop that lifts a Boot 2.7.3 project to 2.7.18 has moved it to the newest
     * patch of a line that cannot run the target. The measurement agrees: this corpus scores Boot
     * 3.5.x at 1 CRITICAL+HIGH against 2.7.18's tens, and 3.5.16 pins tomcat 10.1.55 -- the exact
     * version trivy names as the fix for what the newest Tomcat 9 still carries.
     *
     * <p>The cost is honest and large: 2 to 3 is the jakarta rename, the highest-variance migration
     * in this system, and the one measured losing 1916 of 2409 tests on a repo that took the jump
     * unprepared. It is attempted anyway because the gate is the arbiter -- a migration that loses a
     * test fails and says so -- and because leaving a project on an EOL line that cannot run its own
     * target is not a smaller risk, only a quieter one.
     */

    /** The family moves in lockstep or the build breaks: they share a version by contract. */
    private static final List<String> TOMCAT_EMBED = List.of(
            "tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket");

    /**
     * Which Spring Boot LINE this project is on: 2, 3, or 0 for no Spring.
     *
     * <p>Structural, from the parent declaration or an imported BOM. A version scraped from anywhere
     * near the word "spring" reads a {@code maven-compiler-plugin 3.8.0} as Boot 3.x, which is how
     * the first detector silently routed projects into the wrong migration.
     */
    int bootLine() throws IOException {
        String v = bootVersion();
        return v.isEmpty() ? 0 : Integer.parseInt(v.substring(0, v.indexOf('.')));
    }

    /**
     * The declared Spring Boot version as {@code major.minor}, or empty for no Spring.
     *
     * <p>The minor is kept because the distance being travelled is the interesting number. Boot 2.7
     * to 3.5 is one minor plus the jakarta rename; Boot 2.0 to 3.5 is seven, and 2.0 is the profile
     * of the run that lost 1916 of 2409 tests. Reporting only the major made those two the same
     * fact, so the corpus could not tell them apart afterwards.
     *
     * <p>THE SPELLINGS ARE MEASURED, NOT IMAGINED. The property arm used to enumerate three exact
     * tag names and matched none of the seventeen property declarations in this corpus: real poms
     * write spring.boot.version and spring-boot-dependencies.version, and the Gradle arm could not
     * see a buildscript classpath coordinate at all. Six Boot projects read as having no Spring.
     */
    String bootVersion() throws IOException {
        Pattern parent = Pattern.compile(
                "<artifactId>\\s*spring-boot-(?:starter-parent|dependencies)\\s*</artifactId>\\s*"
                        + "<version>\\s*\\$?\\{?[^<}]*?(\\d+)\\.(\\d+)", Pattern.DOTALL);
        // spring-boot.version, spring.boot.version, spring-boot-version,
        // spring-boot-dependencies.version, spring.boot.dependencies.version, and so on.
        Pattern property = Pattern.compile(
                "<spring[.-]boot[.-]?(?:dependencies[.-])?version>\\s*(\\d+)\\.(\\d+)");
        // classpath("org.springframework.boot:spring-boot-gradle-plugin:2.7.17")
        Pattern gradleClasspath = Pattern.compile(
                "org\\.springframework\\.boot:spring-boot-gradle-plugin:(\\d+)\\.(\\d+)");
        // id("org.springframework.boot") version "3.1.0"
        Pattern gradlePlugin = Pattern.compile(
                "org\\.springframework\\.boot[\"']?\\s*\\)?\\s*(?:version)?\\s*[\"']?(\\d+)\\.(\\d+)");
        List<Path> files = new ArrayList<>(Walls.poms(ws));
        for (String g : List.of("build.gradle", "build.gradle.kts")) {
            Path f = ws.resolve(g);
            if (Files.isRegularFile(f)) {
                files.add(f);
            }
        }
        for (Path f : files) {
            String text = Files.readString(f).replaceAll("<!--.*?-->", "");
            for (Pattern p : List.of(parent, property, gradleClasspath, gradlePlugin)) {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    return m.group(1) + "." + m.group(2);
                }
            }
        }
        return "";
    }

    /** Dotted version order, so 9.0.105 is newer than 9.0.83 rather than alphabetically older. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && x[i].matches("\\d+") ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && y[i].matches("\\d+") ? Integer.parseInt(y[i]) : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private String readAllBuildFiles() throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path p : Walls.poms(ws)) {
            all.append(Files.readString(p));
        }
        try (var s = Files.walk(ws)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String n = f.toString();
                if ((n.endsWith(".gradle") || n.endsWith(".gradle.kts") || n.endsWith(".toml"))
                        && !n.contains("/node_modules/")) {
                    all.append(Files.readString(f));
                }
            }
        }
        return all.toString().toLowerCase();
    }

    /** The recipe run itself, in the sealed container, under the SOURCE JDK the project still is. */
    /**
     * Run a recipe an agent wrote, under a named JDK.
     *
     * <p>The one way a pin reaches a project. An agent that edits a pom by hand is guessing at
     * placement -- dependencyManagement, a property, a BOM import, a Gradle string -- and the
     * recipes know which is right for the project in front of them, on either build system.
     *
     * <p>ON EITHER BUILD SYSTEM WAS AN ASPIRATION FOR FOUR HUNDRED BUMPS. The recipes always knew;
     * the runner was one maven goal, so on a Gradle project the sentence above was simply false and
     * the phase that owed the pin had nothing else to reach for. Which actuator runs is decided
     * here, from what is at the root, and is no longer something an agent has to know.
     */
    String apply(String yaml, String jdk) throws IOException {
        Files.writeString(ws.resolve("rewrite.yml"), normalised(yaml));
        switch (actuatorFor(ws)) {
            case "maven":
                return rewrite(jdk);
            case "gradle":
                return rewriteGradle(jdk);
            // A repository carrying both at the root is still one bump, so both run and whichever
            // build system is not really in charge finds nothing to do.
            case "both":
                return rewrite(jdk) + "\n\n" + rewriteGradle(jdk);
            default:
                return "there is no pom.xml and no gradle build file at the root of this "
                        + "workspace, so there is nothing here for a recipe to run against";
        }
    }

    /**
     * WHICH ACTUATOR THIS WORKSPACE NEEDS: maven, gradle, both, or neither.
     *
     * <p>Read from the root, because a recipe run is per repository and not per module. One Gradle
     * invocation at the root reaches every subproject, measured on a multi-project build where the
     * root held no sources at all and the edit landed in lib/build.gradle.
     */
    static String actuatorFor(Path ws) {
        boolean maven = Files.isRegularFile(ws.resolve("pom.xml"));
        boolean gradle = Files.isRegularFile(ws.resolve("build.gradle"))
                || Files.isRegularFile(ws.resolve("build.gradle.kts"))
                || Files.isRegularFile(ws.resolve("settings.gradle"))
                || Files.isRegularFile(ws.resolve("settings.gradle.kts"));
        return maven && gradle ? "both" : maven ? "maven" : gradle ? "gradle" : "";
    }

    /**
     * THE SAME RECIPE DOCUMENT, RUN BY GRADLE.
     *
     * <p>For four hundred bumps this half did not exist. apply_recipe was one command,
     * {@code mvn rewrite-maven-plugin:run}, and half this corpus by repository is Gradle-only, so
     * on those it reported "Goal requires a project to execute but there is no POM" and nothing
     * else, 622 times, without a single success. The pin phases hold no editor, so the phase could
     * read that a floor was violated and had no way at all to act; 453 of those calls were made
     * after the answer was already known, one bump spending 132 of them before passing at 225 CVEs
     * to 225.
     *
     * <p>Nothing about the recipe changes. A document carrying both the maven and the gradle arm
     * runs here with the maven arms as silent no-ops, which is what the apply_recipe description
     * has been telling agents to write all along.
     */
    private String rewriteGradle(String from) {
        try {
            Files.writeString(ws.resolve(INIT), initScript());
            // The wrapper when the project has one, because its version is what the project builds
            // under and the distributions are staged by version; the image's own gradle otherwise,
            // which is the fallback jvmjob already makes.
            String gradle = Files.isRegularFile(ws.resolve("gradlew")) ? "./gradlew" : "gradle";
            String goal = gradle + " --no-daemon --init-script " + INIT + " rewriteRun";
            Shell.Output out = Shell.run(ws, Runner.env(ws), Duration.ofSeconds(2700),
                    hoptools + "/jvm-run", from, "jvmjob", "run", goal);
            return "recipe run (gradle) rc=" + out.code() + "\n" + Runner.tail(out.text());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "recipe run (gradle) failed: " + e.getMessage();
        }
    }

    /**
     * What tells Gradle to apply a plugin the project has never heard of.
     *
     * <p>Every line here was earned by a run that failed without it, on Gradle 5.6.4 through 9.6.1,
     * Groovy and Kotlin, single project and multi, with every upstream host blackholed so the local
     * mirror was the only reachable source.
     */
    String initScript() {
        StringBuilder jars = new StringBuilder();
        for (String coordinate : RECIPE_JARS.split(",")) {
            jars.append("        rewrite \"").append(coordinate.strip()).append("\"\n");
        }
        return """
                // Written by the harness for one invocation. Not the project's file.
                def repoUrl = System.getenv("BJV_REPO_URL") ?: "http://nexus:8081/repository/maven-public/"
                def mirror = { r -> r.url = repoUrl; try { r.allowInsecureProtocol = true } catch (Throwable ignored) { } }

                initscript {
                    // THE ONE SCOPE THE HOST'S OWN MIRROR SCRIPT DOES NOT REACH. nexus-mirror.init.gradle
                    // prepends the mirror to pluginManagement, to buildscript and to project repositories.
                    // An init script's own classpath is resolved before any of those exist, so without
                    // this the plugin is fetched from the open internet or not at all.
                    repositories {
                        maven { r -> r.url = System.getenv("BJV_REPO_URL") ?: "http://nexus:8081/repository/maven-public/"
                                     // Gradle 6+ refuses a plaintext repository without the opt-in and the
                                     // mirror is http. On 5.x the property does not exist and http is fine.
                                     try { r.allowInsecureProtocol = true } catch (Throwable ignored) { } }
                    }
                    dependencies { classpath "%s" }
                }

                rootProject { p ->
                    p.apply plugin: org.openrewrite.gradle.RewritePlugin
                    try { p.repositories.maven(mirror) } catch (Throwable refused) {
                        // FAIL_ON_PROJECT_REPOS rejects the injection. Measured: the run still works
                        // off the settings-level repositories, so this cannot be fatal.
                        p.logger.lifecycle("bjv: project repositories refused (" + refused.message + ")")
                    }
                    p.dependencies {
                %s    }
                    p.rewrite {
                        setConfigFile(p.file("rewrite.yml"))
                        activeRecipe("%s")
                        // Strictly better than the maven side, which reports a name it could not
                        // resolve as a successful run that changed nothing.
                        setFailOnInvalidActiveRecipes(true)
                    }
                }
                """.formatted(REWRITE_GRADLE_PLUGIN, jars, activeRecipe());
    }

    /** The one literal OpenRewrite accepts as a recipe document's type. Anything else is not a recipe. */
    private static final String RECIPE_TYPE = "specs.openrewrite.org/v1beta/recipe";

    /**
     * MAKE AN AGENT'S RECIPE FILE RUNNABLE, because measured over a live sweep none of them were.
     *
     * <p>Eighty-six apply_recipe calls across seven bumps, none of which reached rc=0, in the one
     * mechanism this harness documents as the only way a pin reaches a project. Two causes, both
     * ours to fix rather than the agents':
     *
     * <ul>
     *   <li>THE NAME REACHED MAVEN AS SEVERAL WORDS. It travels as
     *       {@code -Drewrite.activeRecipes=<name>} inside a command string, and OpenRewrite allows
     *       spaces in a name, so {@code name: Upgrade Lombok to 1.18.46} made Maven read "Lombok" as
     *       a goal and fail with {@code Unknown lifecycle phase}. 58 of 77 named recipes had a
     *       space. Rewriting the name beats quoting it: quoting only moves the problem to whichever
     *       character the next name contains, and a name is just an identifier, so the recipe still
     *       does exactly what its author wrote.
     *   <li>THE TYPE WAS A RECIPE ID RATHER THAN THE DOCUMENT KIND. Agents wrote
     *       {@code type: org.openrewrite.semver} or the id of the recipe they wanted. The field is
     *       the document kind and has exactly one legal value; with any other, nothing registers and
     *       the run fails with {@code Recipe(s) not found} naming the recipe that was right there in
     *       the file. Five of those named our own fallback, which is a file with no top-level name
     *       at all.
     * </ul>
     *
     * <p>Both are the harness supplying a fixed header the agent has no reason to memorise. What the
     * agent actually decides, the recipeList, is untouched.
     */
    private static String normalised(String yaml) {
        List<String> lines = new java.util.ArrayList<>(List.of(yaml.split("\n", -1)));
        boolean type = false;
        boolean named = false;
        boolean display = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            // The header belongs to the FIRST document; later ones are separate recipes.
            if (l.startsWith("---")) {
                break;
            }
            if (!type && l.startsWith("type:")) {
                lines.set(i, "type: " + RECIPE_TYPE);
                type = true;
            } else if (!named && l.startsWith("name:")) {
                lines.set(i, "name: " + sanitise(l.substring(5).strip()));
                named = true;
            } else if (!display && l.startsWith("displayName:")) {
                display = true;
            }
        }
        List<String> header = new java.util.ArrayList<>();
        if (!type) {
            header.add("type: " + RECIPE_TYPE);
        }
        if (!named) {
            header.add("name: com.bjv.Bump");
        }
        if (!display) {
            header.add("displayName: the pins this phase owes");
        }
        lines.addAll(0, header);
        return String.join("\n", lines);
    }

    /** A name a shell cannot split, keeping enough of the original to stay recognisable in a log. */
    private static String sanitise(String given) {
        String safe = given.replaceAll("[^A-Za-z0-9._-]+", ".").replaceAll("^\\.+|\\.+$", "");
        return safe.isBlank() ? "com.bjv.Bump" : safe;
    }

    /** The recipe name in whatever rewrite.yml is on disk, so an agent may name its own. */
    private String activeRecipe() {
        try {
            for (String line : Files.readString(ws.resolve("rewrite.yml")).lines().toList()) {
                if (line.startsWith("name:")) {
                    return sanitise(line.substring(5).strip());
                }
            }
        } catch (IOException unreadable) {
            // Fall through to the name this class writes itself.
        }
        return "com.bjv.Bump";
    }


    private String rewrite(String from) {
        String goal = "mvn -B -ntp -U -Denforcer.skip=true " + REWRITE_PLUGIN + ":run"
                + " -Drewrite.configLocation=$(pwd)/rewrite.yml"
                + " -Drewrite.activeRecipes=" + activeRecipe()
                + " -Drewrite.recipeArtifactCoordinates=" + RECIPE_JARS;
        try {
            Shell.Output out = Shell.run(ws, Runner.env(ws), Duration.ofSeconds(2700),
                    hoptools + "/jvm-run", from, "jvmjob", "run", goal);
            return "recipe run rc=" + out.code() + "\n" + Runner.tail(out.text());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "recipe run failed: " + e.getMessage();
        }
    }
}
