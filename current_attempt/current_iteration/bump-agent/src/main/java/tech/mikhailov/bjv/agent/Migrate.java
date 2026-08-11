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
 * <p>THE SPRING LINE IS READ FROM THE PROJECT, NEVER FROM THE TARGET JDK. Selecting the 3.x
 * migration because the target is modern sent a Boot 2.0 project through the 2-to-3 jump: 1297 files
 * rewritten and 1916 of 2409 tests lost. The line a project is ON decides which upgrade it can take.
 */
final class Migrate {

    /** The recipe versions the corpus measured against; pinned so a run is reproducible. */
    private static final String REWRITE_PLUGIN = "org.openrewrite.maven:rewrite-maven-plugin:6.40.0";
    private static final String RECIPE_JARS = "org.openrewrite.recipe:rewrite-migrate-java:3.36.0,"
            + "org.openrewrite.recipe:rewrite-spring:6.31.0";

    private final Path ws;
    private final String hoptools;
    private final Trace trace;

    Migrate(Path ws, String hoptools, Trace trace) {
        this.ws = ws;
        this.hoptools = hoptools;
        this.trace = trace;
    }

    /** Recipes, then floors, then the target sweep. Returns what to tell the agents was done. */
    String run(String from, String to) throws IOException {
        StringBuilder did = new StringBuilder();
        int target = Integer.parseInt(to);

        List<String> recipes = program(target);
        did.append("recipes: ").append(String.join(", ", recipes)).append('\n');
        if (Files.isRegularFile(ws.resolve("pom.xml"))) {
            Files.writeString(ws.resolve("rewrite.yml"), yaml(recipes));
            did.append(rewrite(from)).append('\n');
        } else {
            did.append("no pom.xml: the recipe program is Maven-only, floors still apply\n");
        }

        did.append(floors(target)).append('\n');
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
    private List<String> program(int target) throws IOException {
        List<String> recipes = new ArrayList<>(List.of(
                "org.openrewrite.java.migrate.UpgradePluginsForJava" + target,
                "org.openrewrite.java.migrate.UpgradeBuildToJava" + target,
                "org.openrewrite.java.migrate.jacoco.UpgradeJaCoCo"));
        if (target == 11) {
            recipes.add("org.openrewrite.java.migrate.Java8toJava11");
        }
        int boot = bootLine();
        if (boot == 2) {
            recipes.add("org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7");
        } else if (boot == 3) {
            recipes.add("org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5");
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
        Pattern parent = Pattern.compile(
                "<artifactId>\\s*spring-boot-(?:starter-parent|dependencies)\\s*</artifactId>\\s*"
                        + "<version>\\s*\\$?\\{?[^<}]*?(\\d+)\\.\\d+", Pattern.DOTALL);
        Pattern property = Pattern.compile(
                "<spring-boot(?:\\.version|-version|\\.dependencies\\.version)>\\s*(\\d+)\\.\\d+");
        Pattern gradlePlugin = Pattern.compile(
                "org\\.springframework\\.boot[\"']?\\s*\\)?\\s*(?:version)?\\s*[\"']?(\\d+)\\.\\d+");
        List<Path> files = new ArrayList<>(Walls.poms(ws));
        for (String g : List.of("build.gradle", "build.gradle.kts")) {
            Path f = ws.resolve(g);
            if (Files.isRegularFile(f)) {
                files.add(f);
            }
        }
        for (Path f : files) {
            String text = Files.readString(f).replaceAll("<!--.*?-->", "");
            for (Pattern p : List.of(parent, property, gradlePlugin)) {
                Matcher m = p.matcher(text);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        }
        return 0;
    }

    /**
     * The measured floors, applied only where the build resolves the thing being floored.
     *
     * <p>They go in as direct {@code dependencyManagement} entries in the root pom: a property
     * override is a silent no-op once a Spring BOM arrives at {@code scope=import}, and
     * {@code UpgradeDependencyVersion} reports BUILD SUCCESS while changing zero files in exactly
     * those cases.
     */
    private String floors(int target) throws IOException {
        StringBuilder did = new StringBuilder("floors: ");
        String all = readAllBuildFiles();
        if (all.contains("lombok")) {
            String v = target >= 25 ? "1.18.46" : "1.18.30";
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
            String bb = target >= 25 ? "1.17.6" : "1.14.12";
            Pom.manage(ws, "net.bytebuddy", "byte-buddy", bb);
            Pom.manage(ws, "net.bytebuddy", "byte-buddy-agent", bb);
            Pom.manage(ws, "org.mockito", "mockito-core", "5.18.0");
            did.append("byte-buddy ").append(bb).append(" + mockito 5.18.0; ");
        }
        if (target >= 25 && all.contains("kotlin")) {
            Pom.setPropertyEverywhere(ws, "kotlin.version", "2.3.20");
            did.append("kotlin 2.3.20; ");
        }
        return did.toString();
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
