package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tech.mikhailov.bjv.engine.Version;
import tech.mikhailov.bjv.jvm.Declared;
import tech.mikhailov.bjv.jvm.Modules;

/**
 * WHAT MANAGES A MODULE'S VERSIONS. A FACT, WITH NO OPINION ATTACHED.
 *
 * <p>Versions are managed in two regimes and the knowledge each needs is almost disjoint. On a
 * Spring Boot module you raise Boot and Tomcat, Jackson and Lombok move with it, and pinning a
 * managed artifact directly overrides the managed set and breaks the thing you were trying to fix.
 * On a module nothing manages you raise every artifact yourself and own the conflicts. A prompt
 * written for one regime is actively wrong in the other, which is why the prompts inside the module
 * walk are keyed by platform and why something has to say which platform a module is in.
 *
 * <p>That something is an agent. This is the {@link Declared} pattern: a tool states facts, a prompt
 * judges them. Nothing here returns a platform, scores a match or filters a row for relevance, and
 * the {@link #PLATFORMS} list is a vocabulary rather than a verdict.
 *
 * <p>THE REGIME IS PER MODULE, NOT PER REPOSITORY, which is the measurement that made this class
 * necessary. Spring Boot manages 73 of 146 repositories in this corpus but only 277 of 771 modules;
 * 16 of the 27 multi-module Boot repositories contain at least one module Boot does not manage, and
 * 80 of the 357 modules inside Boot repositories are outside the managed set entirely. Asking the
 * repository gets one module in five wrong.
 *
 * <p>WHY A PREDICATE CANNOT ANSWER IT. Of the 277 Boot-managed modules, 41 name
 * {@code spring-boot-starter-parent} as their own parent and would be found by a string match. The
 * other 236 would not: 185 inherit an in-repo parent pom that is itself Boot, which is only visible
 * after following the chain to its outermost pom; 40 carry the Gradle plugin; 11 import
 * {@code spring-boot-dependencies} at import scope. And of the 54 import-scope declarations in the
 * corpus, two are written {@code <artifactId>${quarkus.platform.artifact-id}</artifactId>}, so no
 * literal match will ever find them, and a reader that silently drops what it cannot resolve reports
 * a Quarkus module as managed by nothing.
 *
 * <p>So this follows the chain, reports the unresolved property as the property it is, resolves it
 * out loud where the repository declares it, and prints every section even when the section is
 * empty. "Nothing manages this module" and "this was not looked at" are different answers and a
 * reader acting on the second goes hunting for a file that does not exist.
 */
final class Managed {

    /**
     * The closed set of answers the platform detector may give.
     *
     * <p>Three, because the prompt fragments are written per platform and a fourth word would name a
     * directory that does not exist. {@code adhoc} is the fallback as well as a real answer: it is
     * the regime that owns its own conflicts, which is also the safest thing to tell an agent when
     * detection failed, since it asks for evidence before every pin rather than trusting a managed
     * set that may not be there.
     */
    static final List<String> PLATFORMS = List.of("spring-boot", "quarkus", "adhoc");

    /** One build file this report read, and whether it belongs to the module or sits above it. */
    private record Source(String path, String text, boolean above) {

        String where() {
            return above ? path + ", which is above this module" : path;
        }
    }

    /** A whole {@code <dependency>} element, so its scope can be read before its coordinates. */
    private static final Pattern DEPENDENCY =
            Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);

    /** The scope that makes a dependency a BOM import rather than something to compile against. */
    private static final Pattern IMPORT_SCOPE =
            Pattern.compile("<scope>\\s*import\\s*</scope>");

    /** A plugin id in a plugins block, in either Gradle dialect, with or without a version. */
    private static final Pattern PLUGIN_ID =
            Pattern.compile("id\\s*[(\\s]\\s*['\"]([^'\"]+)['\"]");

    /**
     * The three calls that import a managed set in Gradle, with a parenthesised argument or a bare
     * string, because {@code mavenBom 'g:a:v'} inside a Groovy imports block has no parentheses and
     * {@code platform(libs.boot.bom)} has no string.
     */
    private static final Pattern BOM_CALL = Pattern.compile(
            "\\b(platform|enforcedPlatform|mavenBom)\\s*(\\([^)\\n]*\\)|['\"][^'\"]*['\"])");

    /** A property reference, which is the form two of this corpus's BOM imports are written in. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * The line a detector is asked to answer on, wherever markdown and enthusiasm have left it.
     *
     * <p>Bold markers either side of the label, a leading bullet, a heading hash and a quote marker
     * are all things a model does to a line it was told to write exactly, and none of them changes
     * what it said.
     */
    private static final Pattern PLATFORM_LINE =
            Pattern.compile("(?im)^[\\s>*#`\\-]*platform[\\s*`]*[:=][\\s*`]*(.+)$");

    private Managed() {
    }

    /**
     * Everything this repository says about what manages one module's versions.
     *
     * <p>Scoped to the module and then deliberately widened upwards, because management arrives from
     * above by design: the module's own pom is the minority case and the pom four directories up is
     * the majority one. What is the module's own and what reached it from a parent is marked on
     * every row rather than merged, since raising a version in a pom this module merely inherits is
     * an edit to somebody else's module.
     *
     * <p>Sections are printed whether or not they have rows. An empty section is the answer to "what
     * imports a BOM here", and it is a different answer from a missing section.
     */
    static String report(Path ws, Modules.Module m, List<Modules.Module> all) throws IOException {
        List<Path> files = Modules.buildFilesOf(ws, m, all);
        StringBuilder out = new StringBuilder("module ").append(label(m)).append('\n');
        if (files.isEmpty()) {
            return out.append("  (no build file of its own. Whatever manages this module is stated"
                    + " somewhere else, and nothing here can see where.)\n").toString();
        }
        out.append("  build files: ")
                .append(files.stream().map(f -> rel(ws, f)).collect(Collectors.joining(", ")))
                .append('\n');

        List<Path> poms = files.stream()
                .filter(f -> f.getFileName().toString().equals("pom.xml")).toList();
        List<Path> scripts = files.stream()
                .filter(f -> f.getFileName().toString().startsWith("build.gradle")).toList();
        if (!poms.isEmpty()) {
            maven(ws, m, all, poms, out);
        }
        if (!scripts.isEmpty() || poms.isEmpty()) {
            gradle(ws, m, scripts, out);
        }
        return out.toString();
    }

    /**
     * Which platform an answer named, or {@code adhoc} when it named none this walk has prompts for.
     *
     * <p>Only the labelled line counts. A detector's reasoning names every platform it considered,
     * so the first mention of "spring-boot" in an answer is routinely inside the sentence explaining
     * that Boot is NOT what manages this module. Reading the label and nothing else is what keeps
     * the prose free to argue.
     *
     * <p>An unknown word is {@code adhoc} rather than an exception. The detector is one agent pair
     * in a walk of thirty, its answer selects a prompt directory, and a run that dies because a
     * model wrote "micronaut" would lose a bump over a word. The caller records the fallback in the
     * trace, so a detector that keeps missing is countable rather than invisible.
     */
    static String platformIn(String said) {
        if (said == null || said.isBlank()) {
            return "adhoc";
        }
        Matcher line = PLATFORM_LINE.matcher(said);
        if (!line.find()) {
            return "adhoc";
        }
        // WHAT THE WORD IS, not what surrounds it. "spring-boot (the chain reaches 3.2.5)" and
        // "spring_boot" and "Spring Boot" are the same answer, and the tail of the line is the
        // reason rather than a second answer.
        String named = line.group(1).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        String flat = named.replace("-", "");
        for (String platform : PLATFORMS) {
            if (named.equals(platform) || named.startsWith(platform + "-")
                    || flat.startsWith(platform.replace("-", ""))) {
                return platform;
            }
        }
        return "adhoc";
    }

    /** The Maven half: a parent, a chain of parents, and the BOMs any of them import. */
    private static void maven(Path ws, Modules.Module m, List<Modules.Module> all,
                              List<Path> poms, StringBuilder out) throws IOException {
        Path own = m.in(ws).resolve("pom.xml");
        if (!Files.isRegularFile(own)) {
            own = poms.get(0);
        }
        String text = Files.readString(own);

        out.append("\n  the <parent> this module declares\n");
        Declared.Version parent = parentOf(text, rel(ws, own));
        if (parent == null) {
            out.append("    (none. This pom declares no parent, so it inherits nothing that way.)\n");
        } else {
            out.append(String.format("    %-58s %-18s %s%n",
                    parent.coordinates(), parent.value(), parent.file()));
        }

        // THE CHAIN IS THE MAJORITY CASE. 185 of 277 Boot-managed modules name a parent that is
        // itself a module of this repository, and the Boot coordinates only appear at the far end
        // of the walk. A reader that stops at the first parent sees a corporate parent and calls
        // the module unmanaged.
        out.append("\n  the parent chain, followed inside this repository\n");
        List<Source> above = new ArrayList<>();
        chain(ws, all, own, text, out, above);

        List<Source> sources = new ArrayList<>();
        for (Path pom : poms) {
            sources.add(new Source(rel(ws, pom), Files.readString(pom), false));
        }
        sources.addAll(above);
        imports(sources, out);
    }

    /**
     * Every parent pom from this module up to the last one this repository contains.
     *
     * <p>The step out of the repository is reported rather than hidden, because that coordinate is
     * the whole answer for the 41 modules that name a public parent and for the 185 that reach one
     * this way. Where the chain stops inside the repository, that is reported too: a pom that
     * declares no parent manages its own versions and there is nowhere else to look.
     */
    private static void chain(Path ws, List<Modules.Module> all, Path own, String ownText,
                              StringBuilder out, List<Source> above) throws IOException {
        Map<String, Path> index = inRepoPoms(ws, all);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(own.normalize().toString());
        Path at = own;
        String text = ownText;
        while (true) {
            Declared.Version parent = parentOf(text, rel(ws, at));
            if (parent == null) {
                out.append(String.format("    %s declares no parent, so the chain ends here%n",
                        rel(ws, at)));
                return;
            }
            Path next = lookup(index, parent.coordinates());
            if (next == null) {
                out.append(String.format(
                        "    %s inherits %s %s, which is not a module of this repository%n",
                        rel(ws, at), parent.coordinates(), parent.value()));
                return;
            }
            // A pom that resolves back to one already walked is a cycle Maven would reject, and an
            // unguarded walk over it does not terminate.
            if (!seen.add(next.normalize().toString())) {
                out.append(String.format(
                        "    %s inherits %s %s, which is %s, already walked; the chain loops%n",
                        rel(ws, at), parent.coordinates(), parent.value(), rel(ws, next)));
                return;
            }
            out.append(String.format("    %s inherits %s %s, which is %s in this repository%n",
                    rel(ws, at), parent.coordinates(), parent.value(), rel(ws, next)));
            at = next;
            text = Files.readString(next);
            above.add(new Source(rel(ws, at), text, true));
        }
    }

    /**
     * Every BOM imported at import scope, by this module or by a pom above it.
     *
     * <p>An artifactId written as a property is reported as the property, and then resolved out loud
     * against every pom this report read. Both halves matter: dropping the unresolvable row hides
     * the import, and printing only the resolved value hides that the row is an indirection some
     * profile can point somewhere else.
     */
    private static void imports(List<Source> sources, StringBuilder out) {
        out.append("\n  dependencyManagement entries at <scope>import</scope>\n");
        int found = 0;
        for (Source source : sources) {
            Matcher dependency = DEPENDENCY.matcher(withoutXmlComments(source.text()));
            while (dependency.find()) {
                String block = dependency.group(1);
                if (!IMPORT_SCOPE.matcher(block).find()) {
                    continue;
                }
                found++;
                String group = tag(block, "groupId");
                String artifact = tag(block, "artifactId");
                String version = tag(block, "version");
                String type = tag(block, "type");
                out.append(String.format("    %s:%s:%s  type %s  (%s)%n",
                        group.isEmpty() ? "(no groupId stated)" : group,
                        artifact.isEmpty() ? "(no artifactId stated)" : artifact,
                        version.isEmpty() ? "(no version stated)" : version,
                        type.isEmpty() ? "(not stated)" : type,
                        source.where()));
                Matcher placeholder = PLACEHOLDER.matcher(group + " " + artifact + " " + version);
                while (placeholder.find()) {
                    out.append("      ").append(valueOf(placeholder.group(1), sources)).append('\n');
                }
            }
        }
        if (found == 0) {
            out.append("    (none, in this module or in any pom above it inside this repository)\n");
        }
    }

    /** What a property name is worth, said as a reading rather than substituted silently. */
    private static String valueOf(String name, List<Source> sources) {
        for (Source source : sources) {
            String value = tag(withoutXmlComments(source.text()), name);
            if (!value.isEmpty()) {
                return "${" + name + "} = " + value + ", declared in " + source.path();
            }
        }
        return "${" + name + "} is declared in no build file this report read, so what it names"
                + " cannot be seen from here";
    }

    /**
     * The Gradle half: the plugins block, the buildscript classpath, and the BOM calls.
     *
     * <p>A subproject's build script is read together with the root's, because Gradle applies from
     * the root outwards and a subproject that carries Boot through an {@code allprojects} block has
     * a build file that says nothing about Boot at all. Which file a row came from is on the row, so
     * the reader can tell what the module states from what merely reaches it.
     */
    private static void gradle(Path ws, Modules.Module m, List<Path> scripts, StringBuilder out)
            throws IOException {
        List<Source> sources = new ArrayList<>();
        for (Path script : scripts) {
            sources.add(new Source(rel(ws, script),
                    withoutGroovyComments(Files.readString(script)), false));
        }
        if (!m.isRoot()) {
            for (String name : List.of("build.gradle", "build.gradle.kts")) {
                Path root = ws.resolve(name);
                if (Files.isRegularFile(root)) {
                    sources.add(new Source(name, withoutGroovyComments(Files.readString(root)), true));
                }
            }
        }
        // Where an empty section was looked for, which for the root module is one place and not two.
        String nowhere = m.isRoot() ? "    (none in this module)\n"
                : "    (none, in this module or in the root build file)\n";

        // A PLUGIN WITH NO VERSION IS STILL THE PLUGIN. Forty Gradle files in this corpus carry the
        // Boot plugin and many name no version there, because a settings pluginManagement block or
        // the root buildscript already fixed it. Listing only versioned ids reads those modules as
        // managed by nothing.
        out.append("\n  the plugins block\n");
        int found = 0;
        for (Source source : sources) {
            String block = blockOf(source.text(), "plugins");
            if (block.isEmpty()) {
                continue;
            }
            List<Declared.Version> versioned = Declared.in(block, source.path());
            Matcher id = PLUGIN_ID.matcher(block);
            while (id.find()) {
                String name = id.group(1);
                out.append(String.format("    %-50s %-18s %s%n", name,
                        versioned.stream()
                                .filter(v -> v.where().equals("gradle plugin"))
                                .filter(v -> v.coordinates().equals(name))
                                .map(Declared.Version::value)
                                .findFirst().orElse("(no version here)"),
                        source.where()));
                found++;
            }
        }
        if (found == 0) {
            out.append(nowhere);
        }

        out.append("\n  buildscript classpath lines\n");
        found = 0;
        for (Source source : sources) {
            for (String line : blockOf(source.text(), "buildscript").lines().toList()) {
                if (line.strip().startsWith("classpath")) {
                    out.append("    ").append(line.strip()).append("  (")
                            .append(source.where()).append(")\n");
                    found++;
                }
            }
        }
        if (found == 0) {
            out.append(nowhere);
        }

        out.append("\n  platform(), enforcedPlatform() and mavenBom() calls\n");
        found = 0;
        for (Source source : sources) {
            Matcher call = BOM_CALL.matcher(source.text());
            while (call.find()) {
                out.append("    ").append(call.group().replaceAll("\\s+", " ").strip())
                        .append("  (").append(source.where()).append(")\n");
                found++;
            }
        }
        if (found == 0) {
            out.append(nowhere);
        }
    }

    /**
     * Where a parent pom lives, keyed by what a child would call it.
     *
     * <p>Keyed twice, because a module that inherits its groupId from its own parent states no
     * groupId of its own, and the child still names it in full. The artifact-only key resolves that
     * case; the full key is tried first so two modules sharing an artifactId across groups do not
     * swap places.
     */
    private static Map<String, Path> inRepoPoms(Path ws, List<Modules.Module> all)
            throws IOException {
        Map<String, Path> index = new LinkedHashMap<>();
        for (Modules.Module m : all) {
            Path pom = m.in(ws).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            String[] coordinates = coordinatesOf(Files.readString(pom));
            if (coordinates[1].isEmpty()) {
                continue;
            }
            if (!coordinates[0].isEmpty()) {
                index.putIfAbsent(coordinates[0] + ":" + coordinates[1], pom);
            }
            index.putIfAbsent(":" + coordinates[1], pom);
        }
        return index;
    }

    private static Path lookup(Map<String, Path> index, String coordinates) {
        Path exact = index.get(coordinates);
        return exact != null ? exact : index.get(coordinates.substring(coordinates.indexOf(':')));
    }

    /**
     * What a pom calls itself, which is not the first {@code artifactId} in the file.
     *
     * <p>The parent block states an artifactId too, and so does every dependency and every plugin.
     * The project's own is what is left after the parent block is removed and the read stops at the
     * first element that can contain other coordinates.
     */
    private static String[] coordinatesOf(String pom) {
        String clean = withoutXmlComments(pom).replaceAll("(?s)<parent>.*?</parent>", "");
        int cut = clean.length();
        for (String element : List.of("<dependencies>", "<dependencyManagement>", "<build>",
                "<profiles>", "<modules>", "<properties>", "<reporting>")) {
            int at = clean.indexOf(element);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        String head = clean.substring(0, cut);
        return new String[]{tag(head, "groupId"), tag(head, "artifactId")};
    }

    /** The parent {@link Declared} already parses, which is the one this class needs. */
    private static Declared.Version parentOf(String text, String file) {
        return Declared.in(text, file).stream()
                .filter(v -> v.where().equals("parent"))
                .findFirst().orElse(null);
    }

    /**
     * One brace-balanced Gradle block by the name in front of it.
     *
     * <p>Balanced rather than non-greedy: a plugins block is flat, but a buildscript block contains
     * repositories and dependencies, and its first closing brace is three lines above the classpath
     * line the caller came for.
     */
    private static String blockOf(String text, String name) {
        Matcher head = Pattern.compile("(?m)^\\s*" + name + "\\s*\\{").matcher(text);
        if (!head.find()) {
            return "";
        }
        int open = head.end() - 1;
        int depth = 0;
        for (int at = open; at < text.length(); at++) {
            char c = text.charAt(at);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(open + 1, at);
            }
        }
        return text.substring(open + 1);
    }

    /** One element's text, by a name that may contain the dots a property name contains. */
    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + Pattern.quote(name) + ">\\s*([^<]+?)\\s*</"
                + Pattern.quote(name) + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    /** A commented-out import is not an import, which is the rule {@link Declared} already keeps. */
    private static String withoutXmlComments(String text) {
        return text.replaceAll("(?s)<!--.*?-->", "");
    }

    /** The same rule in the other dialect, minus the {@code //} inside every repository URL. */
    private static String withoutGroovyComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(?<!:)//.*$", "");
    }

    private static String label(Modules.Module m) {
        return m.isRoot() ? "root" : m.path();
    }

    private static String rel(Path ws, Path file) {
        return ws.relativize(file).toString().replace('\\', '/');
    }
}
