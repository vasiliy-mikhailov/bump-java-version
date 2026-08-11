package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixer's edits, applied mechanically — and the place its boundaries are ENFORCED.
 *
 * <p>The prompt says "never touch a test", and a prompt is a suggestion. This class is the rule:
 * an edit under a test source root is rejected before anything reaches disk, whatever the model
 * said around it. The same goes for files that do not exist and anchors that are not unique —
 * apply-or-reject, never apply-approximately, because an ambiguous patch applied optimistically is
 * how a workspace ends up in a state no trace can explain.
 */
final class Edits {

    private static final Pattern BLOCK = Pattern.compile(
            "EDIT\\s+(\\S+)\\s*\\n<<<<\\n(.*?)\\n====\\n(.*?)\\n>>>>", Pattern.DOTALL);

    record Applied(int count, String report) {
    }

    private Edits() {
    }

    static boolean declined(String reply) {
        return reply.stripLeading().startsWith("BLOCKED:");
    }

    /** Apply every block or say exactly why one could not be. Nothing is partially applied. */
    static Applied apply(Path ws, String reply) throws IOException {
        Matcher m = BLOCK.matcher(reply);
        List<String[]> blocks = new ArrayList<>();
        while (m.find()) {
            blocks.add(new String[]{m.group(1), m.group(2), m.group(3)});
        }
        if (blocks.isEmpty()) {
            return new Applied(0, "no EDIT blocks in the reply");
        }
        // Validate every block before touching any file: a reply half-applied is worse than one
        // rejected, because the retry then edits a workspace the model has never seen.
        for (String[] b : blocks) {
            String path = b[0];
            if (path.contains("src/test/") || path.contains("src/it/")
                    || path.contains("src/integrationTest/")) {
                return new Applied(0, "rejected: " + path + " is test code, which may not be edited");
            }
            if (path.contains("..")) {
                return new Applied(0, "rejected: " + path + " escapes the workspace");
            }
            Path f = ws.resolve(path);
            if (!Files.isRegularFile(f)) {
                return new Applied(0, "rejected: " + path + " does not exist (a new file is not a patch)");
            }
            String content = Files.readString(f);
            int first = content.indexOf(b[1]);
            if (first < 0) {
                return new Applied(0, "rejected: the anchor text was not found in " + path);
            }
            if (content.indexOf(b[1], first + 1) >= 0) {
                return new Applied(0, "rejected: the anchor text is not unique in " + path
                        + "; include more surrounding lines");
            }
        }
        StringBuilder report = new StringBuilder();
        for (String[] b : blocks) {
            Path f = ws.resolve(b[0]);
            String content = Files.readString(f);
            Files.writeString(f, content.replace(b[1], b[2]));
            report.append(b[0]).append("; ");
        }
        return new Applied(blocks.size(), "edited " + report);
    }
}
