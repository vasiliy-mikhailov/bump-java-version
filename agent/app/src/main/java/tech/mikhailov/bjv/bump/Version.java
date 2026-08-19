package tech.mikhailov.bjv.bump;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import tech.mikhailov.ratchet.config.Env;
import tech.mikhailov.ratchet.record.Digest;
import tech.mikhailov.ratchet.record.Settlement;

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
 * {@link #boms} hashes everything the result is scored against, overrides included. Those two
 * answer "could this have behaved differently". {@link #commit} and {@link #image} answer "where
 * do I go to look", and are stamped at build time because a container cannot otherwise know:
 * lanes run the tag bjv, which every deploy moves.
 *
 * <p>NEITHER OF THE TWO IS COMPUTED HERE. What a run hands its agents is the pipeline's own
 * business, so the pipeline supplies the text through {@link Parts} and this class hashes it and
 * formats the row. The hop arrives as a key for the same reason: this records which variant ran,
 * and it does not have to know that a variant is a pair of Java version numbers.
 */
public final class Version {

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
     * WHAT THE PIPELINE PUTS ITS NAME TO, which is the half of this that this class cannot know.
     *
     * <p>THE DEPENDENCY HAD TO INVERT. Both hashes used to be computed here, by calling the
     * catalogue of Java migration prompts and the bill-of-materials loader directly, so a class
     * whose subject is "which pipeline produced this row" could not be read, tested or moved
     * without the whole migration domain arriving with it. What goes into a fingerprint is
     * inherently the pipeline's business; hashing it is not. So the pipeline hands over the text
     * and this hashes it.
     *
     * <p>THE TEXT, NOT THE HASH, crosses the boundary. An implementation that returned a digest of
     * its own could return one of a different length, or of a different algorithm, and every row
     * on disk is comparable only because all of them were hashed the same way.
     */
    public interface Parts {

        /**
         * Every system prompt this variant of the pipeline will use, as one string, overrides
         * included.
         *
         * <p>Built from the same call the run makes, so a prompt that reaches an agent reaches this
         * and one that does not cannot. Hashing the files on disk instead would miss an override
         * and count a prompt no agent is ever given.
         *
         * <p>{@code results} is the directory the harness was given, because that is what the
         * override store hangs off.
         */
        String prompts(String hop, Path results);

        /** Everything this variant is scored against, as one string, overrides included. */
        String boms(String hop);
    }

    /** Every prompt this variant will use, hashed, overrides included. */
    public static String prompts(String hop, Path results, Parts parts) {
        try {
            return digest(parts.prompts(hop, results));
        } catch (RuntimeException cannotBuild) {
            // A fingerprint nobody can compute is a row without one, never a bump that failed.
            return "";
        }
    }

    /** Everything this variant is scored against, hashed, overrides included. */
    public static String boms(String hop, Parts parts) {
        try {
            return digest(parts.boms(hop));
        } catch (RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * Short and stable. Eight hex characters is enough to group a fortnight of runs.
     *
     * <p>THE HASHING COMES FROM THE LIBRARY NOW, not from a copy kept here. Rows written before
     * the split and rows written after it are comparable only because both sides take the same
     * digest of the same text, so there is one implementation and this is not it.
     */
    private static String digest(String s) {
        return Digest.of(s);
    }

    /** The whole fingerprint as JSON fields, for the settlement row and the trace. */
    static String fields(String hop, Path results, Parts parts) {
        return "\"commit\":\"" + Settlement.escape(commit())
                + "\",\"image\":\"" + Settlement.escape(image())
                + "\",\"prompts\":\"" + prompts(hop, results, parts)
                + "\",\"boms\":\"" + boms(hop, parts) + "\"";
    }
}
