package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
    static Map<ToolSpecification, ToolExecutor> reading(Path root, Trace trace, String agent) {
        return recorded(only(root, Set.of("list_dir", "read_file")), trace, agent);
    }

    /** Read, look around, and edit EXISTING files — outside the test tree. Producers only. */
    static Map<ToolSpecification, ToolExecutor> patching(Path root, Runner runner, String targetJdk,
                                                         Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                only(root, Set.of("list_dir", "read_file", "edit_file"));
        tools.putAll(build(root, runner, targetJdk));
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
