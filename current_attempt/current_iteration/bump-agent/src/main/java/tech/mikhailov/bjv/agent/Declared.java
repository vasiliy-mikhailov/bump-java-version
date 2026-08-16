package tech.mikhailov.bjv.agent;

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
final class Declared {

    /** One version a build file states, and the kind of place it states it. */
    record Version(String coordinates, String value, String where, String file) {
    }

    private Declared() {
    }

    /**
     * Every version every module declares, grouped by module, in reading order.
     *
     * <p>A module that declares nothing is listed and said to declare nothing, because "inherits
     * everything from the parent" and "was not looked at" are different answers and a reader acting
     * on the second would go looking for a file that does not exist.
     */
    static String report(Path ws, List<Modules.Module> modules) throws IOException {
        StringBuilder out = new StringBuilder();
        for (Modules.Module m : modules) {
            List<Path> files = Modules.buildFilesOf(ws, m, modules);
            List<Version> found = new ArrayList<>();
            for (Path f : files) {
                found.addAll(in(Files.readString(f), ws.relativize(f).toString()));
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
            for (Version v : found) {
                out.append(String.format("  %-58s %-18s %s%n",
                        v.coordinates(), v.value(), v.where()));
            }
        }
        return out.isEmpty() ? "no build files found" : out.toString();
    }

    /** Every declaration in one build file, whichever dialect it is written in. */
    static List<Version> in(String text, String file) {
        String clean = text.replaceAll("<!--.*?-->", "");
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
        Matcher dep = Pattern.compile(
                "<(dependency|plugin)>\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?</\\1>", Pattern.DOTALL)
                .matcher(clean);
        while (dep.find()) {
            String block = dep.group();
            String g = one(block, "groupId");
            String a = one(block, "artifactId");
            String v = one(block, "version");
            if (a.isEmpty()) {
                continue;
            }
            put(found, (g.isEmpty() ? "?" : g) + ":" + a,
                    v.isEmpty() ? "(managed elsewhere)" : v,
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

    private static String one(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]+?)\\s*</" + tag + ">").matcher(block);
        return m.find() ? m.group(1) : "";
    }
}
