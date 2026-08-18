package tech.mikhailov.bjv.jvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHAT EACH MODULE DECLARES. A FACT, WITH NO OPINION ATTACHED.
 *
 * <p>This replaces a tool that took the floor list and answered "satisfied" or "outstanding" per
 * pin, and the difference is the whole point. That tool had to be told which artifacts mattered, and
 * it was told by a regex that parsed the same prose the agents were reading. Two readers of one
 * string, and when they disagreed the regex won silently: it looked for
 * {@code org.springframework.boot:spring-boot}, which no application declares, decided the floor was
 * met, and the phase skipped without ever showing an agent the instruction it was gating. Every
 * Spring project in the corpus kept its original Boot version and the log said "every pin met".
 *
 * <p>So the plan, the doing and the checking are the agents' work now, and this only reports. The
 * floor list stays as prose in the prompt, unparsed. A planner reads both and decides what is
 * outstanding — which is a comparison a model does well and a positional {@code split(" ", 3)} does
 * badly, because it can see that {@code spring-boot-starter-parent} is how a Maven project says
 * which Boot it is on, that a starter carries no version because the parent manages it, and that a
 * property is an indirection rather than a value.
 *
 * <p>WHERE a version lives travels with it, because that is what decides which recipe can move it.
 * A version in a parent block needs UpgradeParentVersion; the same number in a property needs
 * ChangePropertyValue; in a dependency it needs UpgradeDependencyVersion. A plan that omits it hands
 * the next agent a guess.
 */
public final class Declared {

    /**
     * What {@link #in} writes where a build file states no version at all.
     *
     * <p>A sentinel and not a message, because the message is assembled per file by
     * {@link #report}, which is the only reader that knows which build file the row came out of and
     * therefore which parent or imported set is claiming it.
     */
    private static final String MANAGED = "(managed elsewhere)";

    /** Said of a managed row when the module's own files name nothing that could be managing it. */
    private static final String NO_MANAGER = "(managed by something this module does not name)";

    /**
     * A {@code <dependency>} or {@code <plugin>} block whose children are all flat tags.
     *
     * <p>One pattern, two readers: {@link #in} takes coordinates out of these blocks and
     * {@link #managedBy} looks through the same blocks for the one carrying
     * {@code <scope>import</scope>}. Spelled out twice it would be one fact in two places, which is
     * the arrangement that let a regex and a prompt disagree about Spring Boot across a whole
     * corpus.
     */
    private static final Pattern BLOCK = Pattern.compile(
            "<(dependency|plugin)>\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?</\\1>", Pattern.DOTALL);

    /** Import scope, which is how a pom says it is pulling in somebody else's managed set. */
    private static final Pattern IMPORT_SCOPE = Pattern.compile("<scope>\\s*import\\s*</scope>");

    /** One version a build file states, and the kind of place it states it. */
    public record Version(String coordinates, String value, String where, String file) {
    }

    private Declared() {
    }

    /**
     * Every version every module declares, grouped by module, in reading order.
     *
     * <p>A module that declares nothing is listed and said to declare nothing, because "inherits
     * everything from the parent" and "was not looked at" are different answers and a reader acting
     * on the second would go looking for a file that does not exist.
     *
     * <p>A ROW WITH NO VERSION NAMES WHAT HOLDS ITS VERSION. It used to read "(managed elsewhere)",
     * a string that occurs in none of the thirty prompt files and in no tool description: the one
     * signal that decides whether an artifact may be raised here at all reached every agent and
     * said nothing to any of them. A blank version is not a missing fact. It is the module stating
     * that another set owns that number, and pinning it locally overrides that set for this single
     * artifact while the rest of the set stays where it was, which is the version skew a bump
     * exists to remove. The coordinates turn the row into the instruction it always was: this one
     * moves when its manager moves, so move the manager.
     *
     * <p>The parent is preferred over an imported BOM, because a module has exactly one parent and
     * any number of imports, and a parent brings both dependencyManagement and pluginManagement
     * with it. Neither is followed past the module's own files: a child managed by an in-repo
     * parent is reported as managed by that parent, and what manages the parent is the parent
     * module's own row, a few lines up in this same report. Resolving that chain here would be this
     * tool deciding something, which is the job it was taken off.
     *
     * <p>Prompts that already know the platform do not make this redundant. 22.4% of the modules
     * inside the Spring Boot repositories in this corpus are not Boot-managed, and a Boot pom
     * carries managed and self-versioned dependencies in the same block. The platform chooses which
     * prompt an agent reads; this row is what tells that agent which coordinates in front of it are
     * not its to set.
     */
    public static String report(Path ws, List<Modules.Module> modules) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Modules.Module m : modules) {
            List<Path> files = Modules.buildFilesOf(ws, m, modules);
            List<Version> found = new ArrayList<>();
            Map<String, String> managers = new LinkedHashMap<>();
            for (Path f : files) {
                String text = Files.readString(f);
                String name = ws.relativize(f).toString();
                List<Version> here = in(text, name);
                managers.put(name, managedBy(text, here));
                found.addAll(here);
            }
            out.append("module ").append(m.isRoot() ? "root" : m.path());
            if (files.isEmpty()) {
                out.append("  (no build file of its own)\n");
                continue;
            }
            if (found.isEmpty()) {
                out.append("  (declares no versions of its own; inherits them)\n");
                continue;
            }
            out.append('\n');
            // THE VALUE COLUMN IS AS WIDE AS THE WIDEST VALUE IN THIS MODULE. A manager's
            // coordinates are several times the width of a version number, and against a fixed
            // column the last field steps in and out as managed and self-versioned rows alternate,
            // which is every Boot pom rather than a corner case.
            int width = 18;
            for (Version v : found) {
                width = Math.max(width, shown(v, managers).length());
            }
            for (Version v : found) {
                out.append(String.format("  %-58s %-" + width + "s %s%n",
                        v.coordinates(), shown(v, managers), v.where()));
            }
        }
        return out.isEmpty() ? "no build files found" : out.toString();
    }

    /** Every declaration in one build file, whichever dialect it is written in. */
    public static List<Version> in(String text, String file) {
        String clean = uncommented(text);
        Map<String, Version> found = new LinkedHashMap<>();

        // <parent><groupId/><artifactId/><version/></parent> — how a Maven project says which
        // Spring Boot, or which corporate parent, it is on. The old check could not see this.
        Matcher parent = Pattern.compile(
                "<parent>\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?<groupId>\\s*([^<]+?)\\s*</groupId>\\s*"
                        + "<artifactId>\\s*([^<]+?)\\s*</artifactId>\\s*<version>\\s*([^<]+?)\\s*"
                        + "</version>", Pattern.DOTALL).matcher(clean);
        while (parent.find()) {
            put(found, parent.group(1) + ":" + parent.group(2), parent.group(3), "parent", file);
        }

        // <dependency> and <plugin>: a version, or none because something else manages it.
        Matcher dep = BLOCK.matcher(clean);
        while (dep.find()) {
            String block = dep.group();
            String g = one(block, "groupId");
            String a = one(block, "artifactId");
            String v = one(block, "version");
            if (a.isEmpty()) {
                continue;
            }
            put(found, (g.isEmpty() ? "?" : g) + ":" + a,
                    v.isEmpty() ? MANAGED : v,
                    dep.group(1).equals("plugin") ? "plugin" : "dependency", file);
        }

        // <properties>: the indirection a dependency's ${...} points at.
        Matcher prop = Pattern.compile("<([a-zA-Z0-9._-]*version)>\\s*([^<$][^<]*?)\\s*</\\1>")
                .matcher(clean);
        while (prop.find()) {
            put(found, "${" + prop.group(1) + "}", prop.group(2), "property", file);
        }

        // Gradle: group:artifact:version in any of the string notations.
        Matcher gradle = Pattern.compile(
                "['\"]([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+):([0-9][0-9A-Za-z.\\-]*)['\"]")
                .matcher(clean);
        while (gradle.find()) {
            put(found, gradle.group(1) + ":" + gradle.group(2), gradle.group(3), "gradle", file);
        }

        // Gradle plugins: id("org.springframework.boot") version "3.1.0"
        Matcher plugin = Pattern.compile(
                "id\\s*[(\\s]\\s*['\"]([^'\"]+)['\"]\\s*\\)?\\s*version\\s*['\"]([^'\"]+)['\"]")
                .matcher(clean);
        while (plugin.find()) {
            put(found, plugin.group(1), plugin.group(2), "gradle plugin", file);
        }

        // The wrapper, which is a version like any other and is pinned like one.
        Matcher wrapper = Pattern.compile("gradle-([0-9]+(?:\\.[0-9]+)*)-(?:bin|all)\\.zip")
                .matcher(clean);
        if (wrapper.find()) {
            put(found, "org.gradle:gradle-wrapper", wrapper.group(1), "wrapper", file);
        }

        return new ArrayList<>(found.values());
    }

    /**
     * Every version any build file states for one artifact, in reading order.
     *
     * <p>For a tool that has to know how far it is being asked to travel before it travels. A
     * coordinate can be declared in several modules at different versions and deciding which one is
     * real is a judgement, so this returns all of them and lets the caller answer for itself.
     *
     * <p>Several spellings are accepted because one artifact has several: a Maven project writes
     * {@code org.springframework.boot:spring-boot-starter-parent}, a Gradle buildscript writes
     * {@code org.springframework.boot:spring-boot-gradle-plugin}, and the plugins block writes the
     * plugin id alone.
     */
    static List<String> valuesFor(Path ws, String... coordinates) throws IOException {
        List<Modules.Module> modules = Modules.of(ws);
        List<String> out = new ArrayList<>();
        for (Modules.Module m : modules) {
            for (Path f : Modules.buildFilesOf(ws, m, modules)) {
                for (Version v : in(Files.readString(f), ws.relativize(f).toString())) {
                    for (String want : coordinates) {
                        if (v.coordinates().equalsIgnoreCase(want) && !out.contains(v.value())) {
                            out.add(v.value());
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * First declaration wins within a file.
     *
     * <p>Not lowest: this reports what the file SAYS, and picking a winner would be a judgement.
     * Whoever reads this can see every module's row and decide; the tool that used to decide is why
     * a repository with one module at the floor read as satisfied everywhere.
     */
    private static void put(Map<String, Version> into, String coordinates, String value,
                            String where, String file) {
        into.putIfAbsent(coordinates + "|" + where, new Version(coordinates, value, where, file));
    }

    /**
     * What a row prints where a version would go: the version, or the name of what holds it.
     *
     * <p>Keyed by file rather than by module. A module directory can hold more than one build file,
     * and the parent that manages a row is the parent written in the file that row came out of.
     */
    private static String shown(Version v, Map<String, String> managers) {
        return MANAGED.equals(v.value()) ? managers.getOrDefault(v.file(), NO_MANAGER) : v.value();
    }

    /**
     * Whatever one build file names as the owner of the versions it leaves blank.
     *
     * <p>The parent first, read back off what {@link #in} already parsed rather than matched a
     * second time. If a pom has one it is the answer, because a parent brings a whole managed set
     * with it and an import brings only what it imports.
     *
     * <p>Then every dependency at import scope, all of them rather than the first, because a module
     * can import several and nothing in the file says which of them owns a given artifact.
     * First-match-wins is the reading that once reported a floor met for a repository sitting below
     * it in every module but one, and a report that lists both leaves the choice with the reader
     * that can make it.
     *
     * <p>An artifactId written {@code ${quarkus.platform.artifact-id}} is printed as written. Two
     * of the fifty-four import-scope declarations in this corpus are spelled that way, so a reader
     * that insisted on literal coordinates would drop the row entirely; the property it points at
     * is itself a row in this same report.
     */
    private static String managedBy(String text, List<Version> declared) {
        for (Version v : declared) {
            if (v.where().equals("parent")) {
                return "(managed by " + v.coordinates() + " " + v.value() + ")";
            }
        }
        List<String> imported = new ArrayList<>();
        Matcher block = BLOCK.matcher(uncommented(text));
        while (block.find()) {
            String b = block.group();
            if (!block.group(1).equals("dependency") || !IMPORT_SCOPE.matcher(b).find()) {
                continue;
            }
            String a = one(b, "artifactId");
            if (a.isEmpty()) {
                continue;
            }
            String g = one(b, "groupId");
            String v = one(b, "version");
            String named = (g.isEmpty() ? "?" : g) + ":" + a + (v.isEmpty() ? "" : " " + v);
            if (!imported.contains(named)) {
                imported.add(named);
            }
        }
        return imported.isEmpty() ? NO_MANAGER : "(managed by " + String.join(", ", imported) + ")";
    }

    /** Comments are not declarations, and a commented-out BOM manages nothing either. */
    private static String uncommented(String text) {
        return text.replaceAll("<!--.*?-->", "");
    }

    private static String one(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(block);
        return m.find() ? m.group(1) : "";
    }
}
