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

    /** Recipes, then floors, then the target sweep. Returns what to tell the agents was done. */
    String run(String from, String to) throws IOException {
        return run(from, to, null);
    }

    String run(String from, String to, Security.Scan before) throws IOException {
        StringBuilder did = new StringBuilder();
        int target = Integer.parseInt(to);

        List<String> recipes = program(Integer.parseInt(from), target);
        did.append("recipes: ").append(String.join(", ", recipes)).append('\n');
        if (Files.isRegularFile(ws.resolve("pom.xml"))) {
            Files.writeString(ws.resolve("rewrite.yml"), yaml(recipes));
            did.append(rewrite(from)).append('\n');
        } else {
            // SAY THAT NONE OF THAT RAN. The recipe list is printed above before anything executes,
            // so on a Gradle build the trace read as though the Spring migration had been applied
            // when the rewrite plugin had never been invoked and the floors had all stood down. A
            // reader cannot audit a decision the log misreports, and this one matters: a Gradle Boot
            // 2 project bumped to 21 keeps a line that cannot run its target.
            did.append("no pom.xml: NONE of the recipes above ran, and the floors below are skipped;"
                    + " this is a Gradle build and the preparer handles its version work\n");
        }

        did.append(floors(target, before)).append('\n');
        did.append(propagate(target));
        trace.applied("migrate", did.toString());
        return did.toString();
    }

    /**
     * The recipe list, by target and by what the project is on.
     *
     * <p>{@code UpgradeJaCoCo} is listed explicitly because it is NOT reachable from
     * {@code UpgradePluginsForJavaN} — traversing both chains showed the program never called it,
     * while a repo's JaCoCo move was being credited to it.
     */
    private List<String> program(int from, int target) throws IOException {
        List<String> recipes = new ArrayList<>(List.of(
                "org.openrewrite.java.migrate.UpgradePluginsForJava" + target,
                "org.openrewrite.java.migrate.UpgradeBuildToJava" + target,
                "org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo"));
        // EVERY LTS STEP THE HOP SPANS, not just the one it lands on. A hop is prescribed and need
        // not be one step: 11->25 crosses 17 and 21, and the walls at those levels do not go away
        // because nothing stopped there. Java8toJava11 in particular is the only recipe that
        // handles the modules JEP 320 removed, and a 8->17 hop needs it as much as 8->11 does.
        for (int step : LTS) {
            if (step > from && step <= target && step == 11) {
                recipes.add("org.openrewrite.java.migrate.Java8toJava11");
            }
        }
        int boot = bootLine();
        if (boot > 0 && target >= 17) {
            // THE LINE THE TARGET CAN RUN, not the newest patch of the line it is on. Boot 4
            // declares java.version 17, so from that target up it is reachable and 2.7 is not
            // merely old, it is unable to run what it is being asked to run. The recipe stops at
            // 4_0 because rewrite-spring 6.31.0 has no 4_1; the floor lifts the patch afterwards.
            recipes.add("org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0");
        } else if (boot == 2) {
            recipes.add("org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7");
        }
        return recipes;
    }

    private String yaml(List<String> recipes) {
        StringBuilder y = new StringBuilder("type: specs.openrewrite.org/v1beta/recipe\n"
                + "name: com.bjv.Bump\nrecipeList:\n");
        recipes.forEach(r -> y.append("  - ").append(r).append('\n'));
        return y.toString();
    }

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

    /**
     * The measured floors, applied only where the build resolves the thing being floored.
     *
     * <p>They go in as direct {@code dependencyManagement} entries in the root pom: a property
     * override is a silent no-op once a Spring BOM arrives at {@code scope=import}, and
     * {@code UpgradeDependencyVersion} reports BUILD SUCCESS while changing zero files in exactly
     * those cases.
     */
    private String floors(int target, Security.Scan before) throws IOException {
        StringBuilder did = new StringBuilder("floors: ");
        if (!Pom.isMaven(ws)) {
            // The floors below are dependencyManagement entries, which a Gradle build does not
            // have. The preparer carries the Gradle equivalents; saying so is the honest record.
            return "floors: skipped, no root pom (Gradle build); the preparer handles this hop's "
                    + "floors for Gradle";
        }
        String all = readAllBuildFiles();
        if (all.contains("lombok")) {
            String v = Floors.version("lombok", target);
            Pom.manage(ws, "org.projectlombok", "lombok", v);
            did.append("lombok ").append(v).append("; ");
            if (target >= 25) {
                // JDK 23+ stopped running classpath annotation processors by default, so a floored
                // Lombok that is never invoked is the same as no Lombok at all.
                Pom.setPropertyEverywhere(ws, "maven.compiler.proc", "full");
                did.append("maven.compiler.proc=full; ");
            }
        }
        if (all.contains("jacoco")) {
            Pom.pluginVersion(ws, "jacoco-maven-plugin", "0.8.15");
            did.append("jacoco 0.8.15; ");
        }
        if (all.contains("mockito") || all.contains("byte-buddy") || all.contains("mockk")) {
            String bb = Floors.version("byte-buddy", target);
            Pom.manage(ws, "net.bytebuddy", "byte-buddy", bb);
            Pom.manage(ws, "net.bytebuddy", "byte-buddy-agent", bb);
            Pom.manage(ws, "org.mockito", "mockito-core", "5.18.0");
            did.append("byte-buddy ").append(bb).append(" + mockito 5.18.0; ");
        }
        if (target >= 25 && all.contains("kotlin")) {
            Pom.setPropertyEverywhere(ws, "kotlin.version", "2.3.20");
            did.append("kotlin 2.3.20; ");
        }
        did.append(spring(target));
        did.append(tomcat(target, before));
        return did.toString();
    }

    /**
     * Land Spring Boot on the measured-best patch of the line the recipe moved it to.
     *
     * <p>The recipe knows which LINE to go to; it does not know which patch of that line this
     * corpus scores best, and it targets whatever it was built against. One property, which is
     * what a Boot project reads whether the BOM arrives through a parent or an import.
     */
    private String spring(int target) throws IOException {
        if (!Pom.isMaven(ws)) {
            return "";
        }
        int line = bootLine();
        if (line == 0) {
            return "";
        }
        // THE TABLE DECIDES, NOT THIS METHOD. Floors carries 2.7.18 below target 17 and 4.1.0 from
        // 17 up, and the preparer's instructions are generated from the same rows, so the version
        // an agent is told and the version the code writes cannot drift apart.
        String want = Floors.version("spring-boot", target);
        if (want.isBlank()) {
            return "";
        }
        // NEVER DOWNWARDS. bootLine() reports whatever major it finds, and a project already above
        // the floor is one this should leave alone: writing 4.1.0 over a Boot 5 project would be a
        // downgrade dressed as a floor.
        if (compare(bootVersion(), want) >= 0) {
            return "spring-boot " + bootVersion() + " already at or above the floor; ";
        }
        String was = bootVersion();
        // THE PARENT FIRST, BECAUSE THAT IS WHAT DECIDES. The recipe moves the parent to whatever
        // version it was built against; this floor exists to land on the patch the corpus measured
        // best, and only the parent pin can overrule the recipe on a parent-declared project.
        boolean parent = Pom.parentVersion(ws, "spring-boot-starter-parent", want);
        Pom.setPropertyEverywhere(ws, "spring-boot.version", want);
        Pom.pluginVersion(ws, "spring-boot-maven-plugin", want);
        return "spring-boot " + was + " -> " + want + (parent ? " (parent)" : " (property)") + "; ";
    }

    /**
     * Lift Tomcat to the newest patch of the line the project is already on.
     *
     * <p>GATED ON THE LINE, NOT ON THE WORD "TOMCAT". Tomcat 10 renamed javax.* to jakarta.*, so
     * pinning a 9.0.x onto a Boot 3 project that resolved 10.1.x would not be a floor, it would be
     * a source-breaking downgrade. The trigger is therefore the version the BEFORE SCAN actually
     * resolved, which is why the scan runs ahead of this: without a measurement there is no way to
     * tell a Tomcat 9 project from a Tomcat 10 one by looking at the build files.
     *
     * <p>Both mechanisms, because either alone is a silent no-op half the time: the property is
     * what Boot's BOM reads when it arrives through a parent, and a dependencyManagement entry is
     * what wins when the BOM arrives at scope=import. That is the same trap the Lombok floor was
     * measured into.
     */
    private String tomcat(int target, Security.Scan before) throws IOException {
        // 21 AND UP, NOT 21 EXACTLY. The floor's whole argument is that 9.0.105 is the newest
        // patch of the line the project is on and carries the fewest CVEs of any of them. Nothing
        // in that reasoning mentions the target, so scoping it to one hop left every 21-to-25 bump
        // on whatever Tomcat it happened to have.
        if (target < 21 || before == null || !before.measured() || !Pom.isMaven(ws)) {
            return "";
        }
        if (bootLine() > 0) {
            // Spring owns tomcat.version, and Boot 3.5 brings 10.1.55, which is newer than any
            // Tomcat 9. Pinning a 9.0.x underneath it would be a downgrade across the jakarta line.
            return "tomcat left to spring-boot " + Floors.version("spring-boot", target) + "; ";
        }
        String resolved = before.inventory().stream()
                .filter(p -> p.name().endsWith(":tomcat-embed-core"))
                .map(Security.Pkg::version).findFirst().orElse("");
        if (!resolved.startsWith("9.0.")) {
            return resolved.isBlank() ? "" : "tomcat " + resolved + " left alone (not the 9.0 line); ";
        }
        if (compare(resolved, TOMCAT_9) >= 0) {
            return "tomcat " + resolved + " already at or past " + TOMCAT_9 + "; ";
        }
        Pom.setPropertyEverywhere(ws, "tomcat.version", TOMCAT_9);
        for (String artifact : TOMCAT_EMBED) {
            Pom.manage(ws, "org.apache.tomcat.embed", artifact, TOMCAT_9);
        }
        return "tomcat " + resolved + " -> " + TOMCAT_9 + " (family in lockstep); ";
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

    /**
     * Raise the java target in EVERY pom, at text level.
     *
     * <p>Not a parser: re-serialising strips comments and licence headers and reorders attributes,
     * and these files go through a diff-based review where a spurious change is noise someone has to
     * argue with. Anchored edits keep the diff exactly as large as the change.
     */
    private String propagate(int target) throws IOException {
        int changed = 0;
        List<Pattern> pins = List.of(
                Pattern.compile("(<maven\\.compiler\\.(?:source|target|release)>)\\s*(?:1\\.)?(\\d+)\\s*(</)"),
                Pattern.compile("(<(?:java|jdk|java\\.source)\\.version>)\\s*(?:1\\.)?(\\d+)\\s*(</)"),
                Pattern.compile("(<(?:source|target|release|testSource|testTarget)>)\\s*(?:1\\.)?(\\d+)\\s*(</)"));
        for (Path pom : Walls.poms(ws)) {
            String text = Files.readString(pom);
            String out = text;
            for (Pattern p : pins) {
                Matcher m = p.matcher(out);
                StringBuilder b = new StringBuilder();
                while (m.find()) {
                    int at = Integer.parseInt(m.group(2));
                    m.appendReplacement(b, Matcher.quoteReplacement(
                            m.group(1) + (at < target ? String.valueOf(target) : m.group(2))
                                    + m.group(3)));
                }
                m.appendTail(b);
                out = b.toString();
            }
            if (!out.equals(text)) {
                Files.writeString(pom, out);
                changed++;
            }
        }
        return "target " + target + " propagated into " + changed + " pom(s)";
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
    private String rewrite(String from) {
        String goal = "mvn -B -ntp -U -Denforcer.skip=true " + REWRITE_PLUGIN + ":run"
                + " -Drewrite.configLocation=$(pwd)/rewrite.yml"
                + " -Drewrite.activeRecipes=com.bjv.Bump"
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
