package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * THE WORKSPACE AS A MODEL MAY TOUCH IT: list a directory, read a file, change a file in place.
 *
 * <p>These three used to arrive from a jar, and they are the only part of that jar this program
 * ever ran. They live here now for the same reason {@link Looking} writes grep and glob here: a
 * tool an agent depends on is part of this program whether or not somebody else also wrote one.
 *
 * <p>NOTHING WRITES A NEW FILE. The set that arrived from outside had a fourth tool that created
 * files, and no phase here was ever given it, on the rule that a new file is not a migration step.
 * It is not reimplemented, because a tool nobody may call is a tool that will eventually be handed
 * to somebody by accident.
 *
 * <p>THE ANSWERS ARE STRINGS AND SO ARE THE FAILURES. Every one of these catches its own
 * exceptions, because a tool executor that throws does not degrade into something the model can
 * read: langchain4j rethrows it and the agent's whole turn dies. The exact failure texts are kept
 * from what ran before, inconsistent prefixes included -- {@code Error: } for a validation or IO
 * failure but a bare {@code Not a file: } and {@code Not a directory: } for the two commonest ones
 * -- because they are what sixty-five agents have been reading. Nothing in this program matches on
 * them; the models do.
 *
 * <p>THE DESCRIPTIONS ARE NOT THE OLD ONES, AND THAT IS A PROMPT CHANGE. They say the same
 * operative things: which JSON key, what a path is relative to, that a match is exact and must be
 * unique, what replace_all is for. Two defects are not carried over. The old edit text claimed the
 * model must use its "Read tool" before editing and that the tool would error otherwise, which was
 * false and unenforced and named a tool that does not exist in this harness, and it ended in the
 * stray fragment {@code /**} left behind by whoever copied it. Reproducing that faithfully would
 * mean reproducing the defect, so the advice survives as advice and the false threat does not.
 *
 * <p>THE SANDBOX CHECK IS TEXTUAL, deliberately and unchanged: a path is normalised and tested for
 * being under the root, and real paths are never resolved. A symlink inside a repo that points out
 * of it is therefore followed. Tightening that would start refusing reads in repos that contain
 * one, which is a change worth making on evidence rather than in passing.
 */
final class Workspace {

    /** Above this many bytes a file is refused rather than returned. Decimal, not 512 KiB. */
    private static final int TOO_LARGE = 512_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String LIST_DIR = """
            Lists what is at one path inside the workspace: the names of the files and \
            subdirectories there, one per line, with a trailing / on the directories. Use "." for \
            the workspace root. This is one level and not a walk, so use glob to find files by path \
            across the whole tree.""";

    private static final String READ_FILE = """
            Reads one text file from the workspace and returns its raw UTF-8 content, with no line \
            numbers.

            The JSON argument is the string property `path`, and it is required. `file_path` and \
            every other name is ignored, so a call that uses one reads nothing. The path is \
            relative to the workspace root, for example `pom.xml` or `core/build.gradle`; an \
            absolute path is accepted only when it still resolves inside the same workspace.

            A path that is missing, is not a file, or leaves the workspace comes back as an error \
            string rather than as a failure, and so does a file too large to return.

            You may ask for several paths in one turn when that is what you need.""";

    private static final String EDIT_FILE = """
            Replaces an exact substring in one file that already exists in the workspace. This is \
            the only way to change a file here; nothing creates one.

            old_string must match the file exactly, whitespace and indentation included, and it \
            must be unique in the file. Where it is not unique, either widen it with surrounding \
            context until it is, or pass replace_all true to change every occurrence, which is what \
            renaming something across a file wants. An old_string that is not found, or that is \
            found more than once without replace_all, changes nothing and says which.

            The path is relative to the workspace root, or absolute within it. Read the file before \
            you edit it: nothing here enforces that, and an edit written from memory is the usual \
            way to send an old_string that does not match.""";

    private final Path root;

    /**
     * SUCCESSFUL READS, KEYED BY ABSOLUTE PATH AND LAST-MODIFIED MILLISECOND.
     *
     * <p>One of these is built per agent, so nothing is shared between them and the cache only ever
     * saves an agent re-reading a file it already read in the same conversation. Invalidation is by
     * modification time, so anything that moves a file -- an edit here, a recipe, a build -- is
     * picked up. The hazard it leaves is a file rewritten twice inside one millisecond, which would
     * serve the first content; it has never been observed, and the alternative was measured to be
     * invisible from outside.
     */
    private final ConcurrentHashMap<String, Cached> reads = new ConcurrentHashMap<>();

    private record Cached(long modifiedMillis, String content) {
    }

    Workspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /**
     * The three of them, in the order a phase would meet them: look, read, change.
     *
     * <p>{@link Looking} keeps the subset a phase should hold and fails loudly if it asks for a name
     * that is not here, which is what catches a rename or a typo at startup rather than at the
     * first call.
     */
    static Map<ToolSpecification, ToolExecutor> tools(Path root) {
        Workspace ws = new Workspace(root);
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();

        tools.put(ToolSpecification.builder()
                .name("list_dir")
                .description(LIST_DIR)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path",
                                "Directory path relative to workspace (e.g. . or subdir)")
                        .required("path")
                        .build())
                .build(), (request, memoryId) -> {
                    try {
                        JsonNode asked = JSON.readTree(request.arguments());
                        // A missing path means the workspace root, which is what a model that
                        // opened with an empty object meant.
                        return ws.listDir(asked.path("path").asText("."));
                    } catch (Exception e) {
                        return "Error: " + e.getMessage();
                    }
                });

        tools.put(ToolSpecification.builder()
                .name("read_file")
                .description(READ_FILE)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path",
                                "Required. Workspace-relative file path (JSON key must be "
                                        + "\"path\", not file_path).")
                        .required("path")
                        .build())
                .build(), (request, memoryId) -> {
                    try {
                        JsonNode asked = JSON.readTree(request.arguments());
                        String path = asked.path("path").asText("");
                        // THE ONE MISTAKE WORTH ANSWERING BY NAME. Models reach for file_path
                        // because half the tools they have seen elsewhere use it, and a read that
                        // silently returns nothing costs the agent a round for no reason. This
                        // never touches the disk; it hands back the key it should have used.
                        if (path.isBlank() && asked.has("file_path")) {
                            return "Error: this tool's JSON schema uses the key \"path\", not "
                                    + "\"file_path\". Retry with {\"path\": \"...\"}.";
                        }
                        return ws.readFile(path);
                    } catch (Exception e) {
                        return "Error: " + e.getMessage();
                    }
                });

        tools.put(ToolSpecification.builder()
                .name("edit_file")
                .description(EDIT_FILE)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("path", "File path relative to workspace")
                        .addStringProperty("old_string",
                                "Exact substring to replace (must be unique unless replace_all)")
                        .addStringProperty("new_string", "Replacement text")
                        .addBooleanProperty("replace_all",
                                "If true, replace every occurrence of old_string")
                        .required("path", "old_string", "new_string")
                        .build())
                .build(), (request, memoryId) -> {
                    try {
                        JsonNode asked = JSON.readTree(request.arguments());
                        String path = asked.path("path").asText("");
                        String oldText = asked.path("old_string").asText("");
                        String newText = asked.path("new_string").asText("");
                        boolean everyOne = asked.path("replace_all").asBoolean(false);
                        if (path.isBlank() || oldText.isEmpty()) {
                            return "Error: path and old_string are required";
                        }
                        return ws.editFile(path, oldText, newText, everyOne);
                    } catch (Exception e) {
                        return "Error: " + e.getMessage();
                    }
                });

        return tools;
    }

    /**
     * A path inside the workspace, or a refusal.
     *
     * <p>Relative paths resolve against the root and absolute ones are taken as given, and either
     * way the normalised result has to still be under the root. Normalised, not made real: see the
     * class comment on what that lets through and why it is left alone.
     */
    private Path inside(String given) throws IOException {
        Path asked = Path.of(given);
        Path resolved = (asked.isAbsolute() ? asked : root.resolve(asked)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Path escapes workspace: " + given);
        }
        return resolved;
    }

    /** One level, names only, directories marked. An empty directory answers with nothing at all. */
    private String listDir(String path) {
        try {
            Path dir = inside(path);
            if (!Files.isDirectory(dir)) {
                return "Not a directory: " + path;
            }
            try (Stream<Path> entries = Files.list(dir)) {
                return entries
                        .map(entry -> Files.isDirectory(entry)
                                ? entry.getFileName() + "/"
                                : entry.getFileName().toString())
                        .sorted()
                        .collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /** The whole file as text, or the reason it is not being returned. */
    private String readFile(String path) {
        try {
            Path file = inside(path);
            if (!Files.isRegularFile(file)) {
                return "Not a file: " + path;
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            String key = file.toString();
            Cached seen = reads.get(key);
            if (seen != null && seen.modifiedMillis() == modified) {
                return seen.content();
            }
            byte[] bytes = Files.readAllBytes(file);
            // Measured after reading, which is the wasteful order and the one that was running: a
            // file this size is rare enough that the extra read costs less than the branch would.
            if (bytes.length > TOO_LARGE) {
                return "File too large (>512KB); use a smaller path or split.";
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            reads.put(key, new Cached(modified, text));
            return text;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * One exact substring replaced, or every occurrence of it, and the file left alone otherwise.
     *
     * <p>THE UNIQUENESS CHECK IS THE POINT. A model that sends a fragment appearing twice usually
     * meant one of them and cannot say which, so the edit is refused with the reason rather than
     * applied to the first match. The answer echoes the ABSOLUTE path, which is the container's
     * path and travels back into the model's context; it is what was being echoed before and
     * nothing downstream reads it, so it is left as it was.
     */
    private String editFile(String path, String oldText, String newText, boolean everyOne) {
        try {
            Path file = inside(path);
            if (!Files.isRegularFile(file)) {
                return "Not a file: " + path;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (!content.contains(oldText)) {
                return "Error: old_string not found in file (must match exactly, including "
                        + "whitespace).";
            }
            String updated;
            if (everyOne) {
                updated = content.replace(oldText, newText);
            } else {
                int at = content.indexOf(oldText);
                if (content.indexOf(oldText, at + oldText.length()) >= 0) {
                    return "Error: old_string is not unique; provide more context or use "
                            + "replace_all=true.";
                }
                updated = content.substring(0, at) + newText
                        + content.substring(at + oldText.length());
            }
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            reads.remove(file.toString());
            return "Updated " + file;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }
}
