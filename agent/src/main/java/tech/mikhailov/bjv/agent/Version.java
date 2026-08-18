package tech.mikhailov.bjv.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * WHICH PIPELINE PRODUCED THIS BUMP, recorded on the bump rather than remembered.
 *
 * <p>A sweep runs for a fortnight and the harness changes daily. On one day this month it was
 * deployed seven times, and three generations of prompt were live in the same sweep at once,
 * because a running lane keeps the image it started with. Every settled row from that fortnight
 * says what happened and nothing about what produced it, so two bumps that disagree cannot be
 * told apart from two pipelines that disagree.
 *
 * <p>THE IMAGE IS NOT THE PIPELINE, which is what makes this more than a label. Prompt edits and
 * bill-of-materials edits live in a store beside the results, outside the image, and take effect
 * on the next lane to start. An image identity alone would be wrong for every run after somebody
 * used the settings page, and wrong in the direction that matters: it would call two runs the same
 * pipeline precisely when one had been edited.
 *
 * <p>So the fingerprint is what the agents were actually handed. {@link #prompts} hashes every
 * system prompt for the hop, overrides included, because that is the set the run will build.
 * {@link #boms} hashes both lists for the hop, overrides included. Those two answer "could this
 * have behaved differently". {@link #commit} and {@link #image} answer "where do I go to look",
 * and are stamped at build time because a container cannot otherwise know: lanes run the tag
 * bjv, which every deploy moves.
 */
final class Version {

    private Version() {
    }

    /** The commit the image was built from, or empty when it was built outside deploy.sh. */
    static String commit() {
        return stamp("bjv.commit");
    }

    /**
     * The image this container was started from, passed in by the launcher.
     *
     * <p>It cannot be stamped at build time: the digest does not exist until the build that would
     * have to contain it has finished. So run.sh resolves what the tag points at and passes it,
     * which is the accurate answer for a lane, because a lane keeps the image it started with
     * however many times the tag moves afterwards.
     */
    static String image() {
        String id = Env.get("BJV_IMAGE_ID", "");
        return id.length() > 19 ? id.substring(0, 19) : id;
    }

    private static String stamp(String key) {
        // ON DISK RATHER THAN ON THE CLASSPATH. The entrypoint is java -cp /bump-agent.jar, so a
        // file written beside it is not a resource, and putting it on the classpath means editing
        // the one line all three entry points start from.
        try {
            Path f = Path.of("/" + key);
            return java.nio.file.Files.isRegularFile(f)
                    ? java.nio.file.Files.readString(f, StandardCharsets.UTF_8).strip() : "";
        } catch (Exception unstamped) {
            return "";
        }
    }

    /**
     * Every system prompt this hop will use, hashed, overrides included.
     *
     * <p>Built from the same call the run makes, so a prompt that reaches an agent reaches this and
     * one that does not cannot. Hashing the files on disk instead would miss an override and count
     * a prompt no agent is ever given.
     */
    static String prompts(Hop hop, Path results) {
        try {
            // THE EDIT, WHERE THERE IS ONE. forHop builds prompts through define, which
            // deliberately does not consult the override store: that catalogue is what the
            // settings page shows beside an edit, so it has to be the code's own text. The live
            // agent is built by runtime, which consults it. Hashing the catalogue alone was
            // therefore blind to the exact case this class exists for, and a test caught it.
            List<String> all = Agents.forHop(hop, results).stream()
                    .map(d -> {
                        String edited = Prompts.override(d.name(), hop);
                        return d.name() + " " + (edited.isBlank() ? d.systemPrompt() : edited);
                    })
                    .sorted()
                    .toList();
            return digest(String.join("", all));
        } catch (RuntimeException cannotBuild) {
            return "";
        }
    }

    /** Both lists for this hop, hashed, overrides included. */
    static String boms(Hop hop) {
        try {
            return digest(Bom.textFor(hop, "enables").text() + Bom.textFor(hop, "hardens").text());
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    /** Short and stable. Eight hex characters is enough to group a fortnight of runs. */
    private static String digest(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                b.append(String.format("%02x", d[i]));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "";
        }
    }

    /** The whole fingerprint as JSON fields, for the settlement row and the trace. */
    static String fields(Hop hop, Path results) {
        return "\"commit\":\"" + Settlement.escape(commit())
                + "\",\"image\":\"" + Settlement.escape(image())
                + "\",\"prompts\":\"" + prompts(hop, results)
                + "\",\"boms\":\"" + boms(hop) + "\"";
    }
}
