package tech.mikhailov.bjv.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A PROMPT SOMEBODY EDITED, WHICH REPLACES THE BUILT-IN ENTIRELY.
 *
 * <p>THERE IS NO MERGE. An edit is the whole prompt or it is nothing, because a prompt half from the
 * code and half from a box is a prompt nobody can read in one place — and reading it in one place is
 * the only way anybody ever works out why an agent did what it did. This is the sibling tool's rule
 * for the same feature and it is the right one.
 *
 * <p>PER AGENT AND PER HOP. The same agent is a different agent on a different hop: an 8-to-11 pin
 * planner is not shown the Kotlin rule for JDK 25, and the text on the settings page is the text for
 * the hop being looked at, floors and all. Storing one override for every hop would take an edit
 * made against 17-to-21's floors and hand it to a bump that cannot reach them.
 *
 * <p>IT TAKES EFFECT ON THE NEXT BUMP THAT STARTS, not on the ones already running. Every agent in a
 * bump is built once, at the top, from whatever was on disk then; a bump that changed its own
 * instructions halfway would be a bump nobody could reproduce.
 *
 * <p>The store sits beside the results rather than inside them: {@code results/} is what the
 * dashboard serves, and a prompt is not a record of anything that happened.
 *
 * <p>THE HOP ARRIVES AS A KEY AND NOTHING MORE. This files text under a name and hands it back;
 * that the name happens to be two Java version numbers is the caller's business, and a store that
 * took the hop itself could not be lifted out of this program without the version ladder coming
 * too. The key every caller passes is the directory name the store has always used, so an
 * override written before this change still loads.
 */
public final class Prompts {

    /**
     * Where overrides live, set once by whoever knows the run root.
     *
     * <p>Static because the alternative is threading a path through every agent factory to serve a
     * feature most bumps never use. It is written before any agent is built and read after, which is
     * the only ordering that matters.
     */
    private static volatile Path store = null;

    private Prompts() {
    }

    /** Point the store at a run root. {@code results} is the directory the harness was given. */
    public static void beside(Path results) {
        Path root = results.getParent() == null ? results : results.getParent();
        store = root.resolve("prompts");
    }

    /** The edited text for one agent on one hop, or empty when the code's own still stands. */
    public static String override(String agent, String hop) {
        return override(store, agent, hop);
    }

    /**
     * The same, against an explicit root.
     *
     * <p>Every rule about edits is here rather than in the static wrapper, so a test can state them
     * without a global. A null root reads as "no edits", because the agent runs in a container that
     * may not have the store mounted at all and "I cannot see the overrides" must mean "there are
     * none" rather than a crash halfway through a bump.
     */
    public static String override(Path root, String agent, String hop) {
        Path file = fileFor(root, agent, hop);
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            String text = Files.readString(file);
            // AN EMPTY FILE IS NOT AN EMPTY PROMPT. It is a save that went wrong or a revert that
            // half happened, and an agent given nothing to do does something arbitrary.
            return text.isBlank() ? "" : text;
        } catch (IOException unreadable) {
            return "";
        }
    }

    public static boolean edited(Path root, String agent, String hop) {
        Path file = fileFor(root, agent, hop);
        return file != null && Files.isRegularFile(file);
    }

    /**
     * Save an edit.
     *
     * <p>Written beside and renamed over, so an agent reading this file while it is being written
     * sees the old text or the new one and never half of each.
     */
    public static void save(Path root, String agent, String hop, String text) throws IOException {
        Path file = fileFor(root, agent, hop);
        if (file == null) {
            throw new IOException("no prompt store configured");
        }
        Files.createDirectories(file.getParent());
        Path staged = file.resolveSibling(file.getFileName() + ".staged");
        Files.writeString(staged, text, StandardCharsets.UTF_8);
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Throw the edit away. The built-in is not restored; it was never gone. */
    public static void revert(Path root, String agent, String hop) throws IOException {
        Path file = fileFor(root, agent, hop);
        if (file != null) {
            Files.deleteIfExists(file);
        }
    }

    /** Every agent with an edit, for the count in the header. */
    public static List<String> editedOn(Path root, String hop) {
        List<String> out = new ArrayList<>();
        Path dir = dirFor(root, hop);
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".txt")) {
                    out.add(name.substring(0, name.length() - 4));
                }
            }
        } catch (IOException unreadable) {
            return out;
        }
        return out;
    }

    /**
     * {@code <root>/<hop>/<agent>.txt}, or null when there is nowhere to put it.
     *
     * <p>The agent name comes off a URL, so it is checked rather than trusted: a name with a slash
     * or a dot-dot in it would write outside the store. Every real name is lower-case letters and
     * hyphens, so anything else is a caller doing something it should not.
     *
     * <p>AND ONE OPTIONAL {@code @platform} TAIL, because fourteen of the agents inside the module
     * walk exist once per platform and carry it in the name: before-pins-doer@spring-boot is a
     * different agent from before-pins-doer@adhoc, handed different text and edited apart. Nothing
     * else about the store changes, which is the point of putting the platform in the name rather
     * than adding a third key to a path, a page and every lookup between them. The tail admits the
     * same characters the name does and no separator, so it still cannot leave the store.
     */
    private static Path fileFor(Path root, String agent, String hop) {
        Path dir = dirFor(root, hop);
        if (dir == null || !agent.matches("[a-z][a-z0-9-]*(@[a-z][a-z0-9-]*)?")) {
            return null;
        }
        return dir.resolve(agent + ".txt");
    }

    /**
     * The directory one variant of the pipeline files its edits under, or null when there is none.
     *
     * <p>The key is checked the way the agent name is, and for the same reason: both reach this
     * class off a URL, and either could otherwise name a directory outside the store. Digits and
     * hyphens is what every real key is, so anything else is a caller doing something it should
     * not.
     */
    private static Path dirFor(Path root, String hop) {
        if (root == null || hop == null || !hop.matches("[a-z0-9][a-z0-9-]*")) {
            return null;
        }
        return root.resolve(hop);
    }
}
