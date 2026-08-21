package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * WHAT THE NEXT LANE IS TOLD TO CALL, AS DATA THE LAUNCHER READS RATHER THAN AS A PAGE'S OPINION.
 *
 * <p>THIS FILE EXISTS BECAUSE THE ONE BEFORE IT WAS DECORATION. {@code model_key} was written by
 * this page, named on this page, and read by exactly two files: the page's own server and its test.
 * No launcher, no lane and no supervisor ever opened it, while the card above it said "what is
 * saved here is what the next launch reads". A control that reports success while the thing it
 * names carries on unchanged is the failure this codebase keeps finding, and it had been built once
 * more.
 *
 * <p>SO THE READER MOVED, AND THAT IS THE WHOLE CHANGE. {@code run.sh} now reads this store on the
 * host once per LANE, next to the {@code docker run} that opens it, in the same precedence this
 * class uses: what is saved here wins, and the environment is underneath. The effect boundary is
 * therefore the next lane rather than the next launcher, which is a stronger promise than the page
 * used to make and the only one that is true.
 *
 * <p>ONE FILE, NOT THREE, because {@code run.sh} has to read it with {@code sed} and because one
 * file has one mtime. That mtime is the evidence the page needs to tell a value that is IN FORCE
 * from one waiting for a launcher that has not started yet: a lane records the mtime it read into
 * {@code settings_seen}, and {@link #laneHasThis()} compares the two. Everything else on that card
 * is a claim about the future; this is a fact about the past.
 *
 * <p>PARSED BY A SHELL, SO NEVER EXECUTABLE BY ONE. The format is {@code name=value} lines with no
 * quoting and no expansion, because the launcher must be able to read it with {@code sed} rather
 * than with {@code .}: this file is written by a page on the public internet, and sourcing it would
 * make a saved value a command the launcher runs as the host user.
 *
 * <p>THE WHOLE FILE IS {@code rw-------}, not just the line that needs it. The key shares the file
 * with the endpoint and the model name, and a mode is a property of a file rather than of a line.
 * That is affordable here because the run root is a bind mount owned by the one user the sweep and
 * this page both run as, and it is load bearing: the dashboard is started with
 * {@code --user $(id -u):$(id -g)} in {@code deploy.sh}, which is the only reason {@code run.sh}
 * can read what this writes.
 *
 * <p>UNREADABLE IS NOT EMPTY. Every read falls back to the environment when the file cannot be
 * parsed, so a chmod accident or a half-written file leaves the pipeline exactly where it was
 * instead of pointing it at nothing. The launcher has the same rule written out in shell, where it
 * is much easier to get backwards, because {@code $(cat missing)} and {@code $(cat unreadable)} are
 * both the empty string and letting either win over {@code $OC_KEY} launches a sweep with no
 * credentials at all.
 */
final class ModelSettings {

    /** The store, beside {@code max_lanes}: the run root is this container's only mount. */
    static final String FILE = "model";

    /**
     * WHAT THE KEY ALONE USED TO LIVE IN, READ AND NEVER WRITTEN AGAIN.
     *
     * <p>A key saved through the old page is still the owner's most recent deliberate statement of
     * what the key should be, and dropping it on an upgrade would be this page unsetting a
     * credential without being asked. It sits under the store and over the environment, and
     * forgetting removes both, because a checkbox that left one of the two behind would be the
     * clearest possible lie on a card whose whole subject is which value is really in force.
     */
    static final String LEGACY_KEY_FILE = "model_key";

    /** What a lane wrote down about the settings it was started with. Never a key, only an mtime. */
    static final String SEEN_FILE = "settings_seen";

    /** Nobody but the user the sweep and this page both run as. */
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path where;
    private final Path legacy;
    private final Path seen;

    ModelSettings(Path runRoot) {
        this.where = runRoot.resolve(FILE);
        this.legacy = runRoot.resolve(LEGACY_KEY_FILE);
        this.seen = runRoot.resolve(SEEN_FILE);
    }

    /** The endpoint the next lane is given. Blank here means the environment's. */
    String endpoint() {
        return read("endpoint", env("OC_BASE"));
    }

    /** The model name the next lane is given. */
    String model() {
        return read("model", env("OC_MODEL"));
    }

    /**
     * The key in force: this page, then the file the old page wrote, then the environment.
     *
     * <p>{@code run.sh} computes this same chain in this same order for every lane it opens, which
     * is the only reason the pill over the field is allowed to say the agents are using it.
     */
    String key() {
        String saved = stored().getOrDefault("key", "");
        if (!saved.isBlank()) {
            return saved;
        }
        String old = text(legacy);
        return old.isBlank() ? env("OC_KEY") : old;
    }

    /** Where the key came from, which is the thing a reader needs before changing it. */
    String keyFrom() {
        if (key().isBlank()) {
            // NOT A GUESS. With no key anywhere there is no source to report, and answering "the
            // environment" would arm a control that offers to drop a key saved here.
            return "";
        }
        return stored().getOrDefault("key", "").isBlank() && text(legacy).isBlank()
                ? "the environment" : "this page";
    }

    /** Whether anything at all is saved here, which is what the card's provenance reports. */
    boolean edited() {
        return Files.isRegularFile(where) || Files.isRegularFile(legacy);
    }

    /** When the key was saved here, or 0 when the key is the environment's. */
    long keyStoredAt() {
        if (!stored().getOrDefault("key", "").isBlank()) {
            return modified(where);
        }
        return text(legacy).isBlank() ? 0L : modified(legacy);
    }

    /** When the most recently started lane started, or 0 when no lane has recorded one. */
    long laneStartedAt() {
        return modified(seen);
    }

    /**
     * WHETHER THE LAST LANE TO START READ WHAT IS ON THIS PAGE NOW.
     *
     * <p>The one fact on this card that is about the past rather than about the next launch. A lane
     * writes the mtime of the store it read; this compares it against the store's mtime now. False
     * means a launcher that started before the last save is still opening lanes with what it read
     * then, which is the state the page must be able to describe rather than paper over.
     *
     * <p>Seconds, because that is the granularity {@code stat} gives a shell, and equality rather
     * than a window: a lane either read this file or it read a different one.
     *
     * <p>NO RECORD IS NOT A YES. With no {@code settings_seen} at all, which is what a launcher
     * started before this feature looks like, nothing has said what any lane read and the honest
     * answer is that this page cannot tell. Returning true because both sides happen to be zero
     * would be the same shape of claim this whole change exists to remove.
     */
    boolean laneHasThis() {
        if (!Files.isRegularFile(seen)) {
            return false;
        }
        long recorded = number(text(seen));
        return recorded == (Files.isRegularFile(where) ? modified(where) / 1000L : 0L);
    }

    /**
     * WRITES A SUBMITTED FORM, RETURNING WHY NOTHING WAS SAVED OR "" WHEN IT WAS.
     *
     * <p>MENTIONED IS NOT THE SAME AS SET, and the two fields on this card mean opposite things by a
     * blank box. The endpoint and the model fall back to the environment when they are emptied,
     * which is how a reader undoes an override without a shell. The key is LEFT ALONE, because a
     * browser that clears the field must not be able to unset a credential and leave every lane
     * talking to an endpoint that refuses it. Only the checkbox drops a key, and it is applied
     * AFTER the value so that forgetting wins over a key sent in the same request.
     *
     * <p>Refused rather than clamped, unlike the sibling, because a value that cannot be used is
     * better rejected where it was typed than silently turned into a different one three hours
     * later in a lane's log.
     */
    String save(Map<String, String> given) throws IOException {
        Map<String, String> now = new LinkedHashMap<>(stored());
        String offeredKey = given.getOrDefault("key", "").trim();
        boolean forget = "1".equals(given.get("forget"));
        if (given.containsKey("model")) {
            String value = given.get("model").trim();
            String why = value.isBlank() ? "" : whyThatIsNotAName(value, "a model name");
            if (!why.isEmpty()) {
                return why;
            }
            put(now, "model", value);
        }
        if (given.containsKey("endpoint")) {
            String value = given.get("endpoint").trim();
            String why = value.isBlank() ? "" : whyThatIsNotAnEndpoint(value);
            if (!why.isEmpty()) {
                return why;
            }
            put(now, "endpoint", value);
        }
        if (!offeredKey.isBlank()) {
            String why = whyThatIsNotAKey(offeredKey);
            if (!why.isEmpty()) {
                return why;
            }
            now.put("key", offeredKey);
        }
        if (forget) {
            now.remove("key");
            try {
                Files.deleteIfExists(legacy);
            } catch (IOException cannot) {
                // The checkbox promises the agents fall back to the environment's key. If the file
                // the old page wrote survives, they will not, so the save is refused rather than
                // reported as a success that left the credential exactly where it was.
                return "the key saved by the older page could not be removed, so the agents would "
                        + "keep using it; drop " + LEGACY_KEY_FILE + " from the run root with a shell";
            }
        }
        if (now.isEmpty()) {
            // NOTHING SAVED IS NOT AN EMPTY FILE. An empty store would leave the card reading
            // "edited" while every value came from the environment, which is the provenance
            // question the card exists to answer.
            Files.deleteIfExists(where);
            return "";
        }
        write(now);
        return "";
    }

    private static void put(Map<String, String> now, String name, String value) {
        if (value.isBlank()) {
            now.remove(name);
        } else {
            now.put(name, value);
        }
    }

    /**
     * WHY A KEY IS REFUSED, IN WORDS THAT DO NOT REPEAT IT.
     *
     * <p>Blank is no longer here, and that is the reversal this class carries. It used to be a
     * refusal on the stated grounds that a save which silently did nothing is its own lie, and that
     * was correct while there was no other way to drop a saved key. There is one now, so blank goes
     * back to meaning leave it alone and the checkbox means drop it, which is what the field's own
     * sentence promises.
     *
     * <p>The rest is shape, and worth keeping over the sibling's nothing at all: what these catch
     * is a paste accident, half a key or a whole line of shell, and the reader finds out where they
     * typed it rather than in a lane's log.
     */
    static String whyThatIsNotAKey(String offered) {
        if (offered.length() < 20) {
            return "that is shorter than any key this endpoint issues";
        }
        if (offered.length() > 200) {
            return "that is longer than a key, so something else came with it";
        }
        return whyThatIsNotAName(offered, "a key");
    }

    /** One run of printable characters, which is what a shell can pass through as one word. */
    private static String whyThatIsNotAName(String offered, String what) {
        for (int i = 0; i < offered.length(); i++) {
            char c = offered.charAt(i);
            if (c < '!' || c > '~') {
                return what + " is one run of printable characters, with no spaces or line breaks "
                        + "anywhere in it";
            }
        }
        return "";
    }

    /**
     * WHY AN ENDPOINT IS REFUSED.
     *
     * <p>The scheme is checked because it is not decoration: the client negotiates HTTP/2 for
     * https and stays on 1.1 for anything else, so this field decides the transport as well as the
     * route. Everything else about the address is the endpoint's business, and a page that tried to
     * validate a host would refuse the internal name somebody deliberately typed.
     */
    private static String whyThatIsNotAnEndpoint(String offered) {
        String why = whyThatIsNotAName(offered, "an endpoint");
        if (!why.isEmpty()) {
            return why;
        }
        if (!offered.startsWith("http://") && !offered.startsWith("https://")) {
            return "an endpoint starts with http:// or https://, and which one decides the protocol";
        }
        return "";
    }

    /**
     * OWNER-ONLY BEFORE IT HOLDS ANYTHING, THEN RENAMED OVER.
     *
     * <p>The mode is a creation attribute rather than a chmod afterwards, because the window
     * between a world-readable create and the chmod that closes it is a window on a bind mount.
     *
     * <p>The rename is what makes this safe to write while a sweep is reading it. A launcher opens
     * this file once per lane, so a truncate-and-rewrite would be read half-written by whichever
     * lane happened to start during it, and a half-read key is a lane that runs unauthenticated.
     */
    private void write(Map<String, String> now) throws IOException {
        StringBuilder text = new StringBuilder();
        now.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
        Path staged = where.resolveSibling(where.getFileName() + ".staged");
        Files.deleteIfExists(staged);
        try {
            Files.createFile(staged, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException notPosix) {
            // A filesystem without POSIX modes is not a reason to refuse the save. The run root
            // here is an ext4 bind mount, so this branch exists for a test on a stranger's machine.
            Files.createFile(staged);
        }
        Files.writeString(staged, text.toString(), StandardCharsets.UTF_8);
        Files.move(staged, where, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** What the file holds, or nothing at all when it cannot be read. */
    private Map<String, String> stored() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            if (!Files.isRegularFile(where) || !Files.isReadable(where)) {
                return out;
            }
            for (String line : Files.readAllLines(where, StandardCharsets.UTF_8)) {
                int at = line.indexOf('=');
                if (at > 0) {
                    out.put(line.substring(0, at).strip(), line.substring(at + 1).strip());
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // A SETTING THAT CANNOT BE READ IS NOT AN EMPTY SETTING, and the launcher agrees in
            // shell. Every caller lands on the environment instead, so the pipeline stays where it
            // was rather than being pointed at nothing.
            return Map.of();
        }
        return out;
    }

    private String read(String name, String fallback) {
        String value = stored().getOrDefault(name, "");
        return value.isBlank() ? fallback : value;
    }

    private static String text(Path file) {
        try {
            return Files.isRegularFile(file) && Files.isReadable(file)
                    ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private static long modified(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException | RuntimeException unreadable) {
            return 0L;
        }
    }

    private static long number(String value) {
        try {
            return value.isBlank() ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    private static String env(String name) {
        return System.getenv().getOrDefault(name, "");
    }
}
