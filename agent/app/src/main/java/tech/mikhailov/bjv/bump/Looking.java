package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

import tech.mikhailov.bjv.engine.Reasoning;

/**
 * READING A WORKSPACE: what every agent gets, whatever else its phase allows.
 *
 * <p>read_file, list_dir and edit_file are {@link Workspace}'s, filtered down to the ones a phase
 * should hold. grep and glob are written here because nothing else was going to write them, and a
 * model asking for a tool that does not exist does not degrade, it throws. All five are this
 * program's own now: the three that used to arrive from a jar were the only part of that jar this
 * program ever ran.
 *
 * <p>Both of the written ones carry a measured correction in their bodies rather than in their
 * prose: which shape of filter a grep will honour, and which build output a glob is allowed to
 * reach. Neither was a matter of taste; each was hundreds of calls that returned nothing.
 */
final class Looking {

    private Looking() {
    }

    /**
     * The file tools, filtered, plus grep and glob.
     *
     * <p>FAILS LOUDLY IF A NAME ASKED FOR IS NOT THERE. langchain4j keys executors by name while
     * advertising the specifications as a list, so a tool that quietly disappears from the set, or
     * one whose name is mistyped where the phase is declared, leaves an agent holding a promise
     * nothing answers. The count is the whole guard, and it is checked at construction rather than
     * at the first call.
     */
    static Map<ToolSpecification, ToolExecutor> only(Path root, Set<String> names) {
        Map<ToolSpecification, ToolExecutor> kept = new LinkedHashMap<>();
        Workspace.tools(root)
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
