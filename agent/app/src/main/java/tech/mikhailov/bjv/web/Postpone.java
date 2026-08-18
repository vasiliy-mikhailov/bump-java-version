package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import tech.mikhailov.bjv.engine.Json;

/**
 * SET A BUMP ASIDE, AND TAKE IT BACK.
 *
 * <p>A postponement is a file and nothing else: {@code results/postponed/<slug>}, which run.sh
 * tests with {@code [ -e ]} before it launches a manifest row, and again from the watchdog inside a
 * lane that is already running. Until now only the supervisor wrote one, from inside a container,
 * off a digest of the whole sweep. A reader watching one bump often knows before the supervisor
 * does that a lane is going nowhere, and had no way to say so.
 *
 * <p>IT GOES BOTH WAYS, WHICH IS MOST OF THE POINT. run.sh does clear these itself, but only on a
 * pass that launched nothing else, so on a sweep with a fortnight of rows left a mis-click would
 * park a repository until the corpus ran dry. Setting and clearing are therefore one class behind
 * two routes, {@code /api/postpone} and {@code /api/resume}: whatever the page can do it can undo,
 * and the two directions cannot drift apart because they are the same code path.
 *
 * <p>THE DIRECTION IS IN THE ROUTE, NOT IN WHAT THE PAGE LAST BELIEVED. A control that only flips
 * acts on the state it rendered with, so two readers on one bump, or one reader on a stale tab,
 * undo each other and neither can tell. Naming the direction makes a second click a no-op. A caller
 * that genuinely has no idea, which is a button with no row behind it, can still ask for
 * {@code state=flip} and get the other one.
 *
 * <p>THE PATH IS THE WHOLE OF THE CORRECTNESS HERE. A marker written under a name run.sh does not
 * test is a button that reports success and changes nothing, which is the bug this codebase keeps
 * finding. So the flattening is {@link Results#slug}, the one the rest of the dashboard already
 * keys traces and settlements by, and the directory is {@link Sweep#postponedDir}. Neither rule is
 * written out a second time in this file. {@code Results.slug} is idempotent, since a run of
 * underscores collapses to one underscore, so this takes the bump key or the slug the page is
 * already holding and lands on the same file either way. It also means a key like
 * {@code ../../etc/passwd} flattens into a file name instead of escaping the directory.
 *
 * <p>WHAT IT REPORTS IS READ BACK OFF DISK, never assumed from what was asked for. The server and
 * the supervisor run as different users and the directory belongs to whichever of them made it
 * first, so a refused write has to read as refused rather than as a postponement that quietly did
 * not happen. See {@link #explain}.
 *
 * <p>The second write this server makes into a live sweep, and it follows the first: like
 * {@link Rerun} it adds a small file of its own rather than rewriting anything a lane is appending
 * to, so there is no window in which a sweep and a reader are writing the same bytes.
 */
final class Postpone {

    /** What the marker says when the page offered no reason of its own. run.sh echoes it. */
    private static final String UNSTATED = "set aside from the dashboard";

    private static final List<String> ON = List.of("on", "true", "1", "yes", "postpone");
    private static final List<String> OFF = List.of("off", "false", "0", "no", "resume");
    private static final List<String> FLIP = List.of("flip", "toggle");

    private final Sweep sweep;

    Postpone(Path results) {
        this.sweep = new Sweep(results);
    }

    /**
     * Set a bump aside, bring it back, or flip whichever it is.
     *
     * @param key      the bump, as its {@code repo|sha|from|to} key or as the slug the page holds
     * @param state    {@code on}, {@code off}, {@code flip}, or blank to take the route's direction
     * @param why      the reason to record, used only when setting one; run.sh prints its first 90
     *                 characters when a lane stops for it
     * @param setAside the direction the route asked for, which is what a blank state means
     */
    String ask(String key, String state, String why, boolean setAside) {
        if (key == null || key.isBlank()) {
            return answer("", false, false, "", "no bump given");
        }
        String slug = Results.slug(key.strip());
        boolean was = sweep.postponed(slug);
        String want = state == null ? "" : state.strip().toLowerCase(Locale.ROOT);
        boolean wanted;
        if (want.isEmpty()) {
            wanted = setAside;
        } else if (ON.contains(want)) {
            wanted = true;
        } else if (OFF.contains(want)) {
            wanted = false;
        } else if (FLIP.contains(want)) {
            wanted = !was;
        } else {
            return answer(slug, was, was, reason(was, slug),
                    "state is on, off or flip, or left out to take the route's own; got " + state);
        }

        String failed = "";
        try {
            if (wanted && !was) {
                sweep.postpone(slug, why == null || why.isBlank() ? UNSTATED : why.strip());
            } else if (!wanted && was) {
                sweep.resume(slug);
            }
        } catch (IOException couldNotWrite) {
            failed = explain(couldNotWrite, wanted);
        }

        // THE ANSWER IS THE DISK, NOT THE REQUEST. Everything above can fail for reasons that
        // belong to another user's umask, and a reply built from what was asked for would report a
        // bump as set aside while the launcher goes on launching it.
        boolean now = sweep.postponed(slug);
        return answer(slug, now, was, reason(now, slug), failed);
    }

    /**
     * EVERY BUMP CURRENTLY SET ASIDE, from one directory listing.
     *
     * <p>A toggle has to render in the state it is already in, and asking per row would be 1439
     * questions to draw one page. The markers are one flat directory, so the whole answer is a
     * listing, and it is the same directory the write above uses.
     */
    String listing() {
        List<String> slugs = new ArrayList<>();
        try (var markers = Files.list(sweep.postponedDir())) {
            markers.map(p -> p.getFileName().toString()).sorted().forEach(slugs::add);
        } catch (IOException noneYet) {
            // No directory is the ordinary state of a sweep with nothing set aside, not an error.
        }
        return Json.object(
                Json.field("dir", Json.string(sweep.postponedDir().toString())),
                Json.field("postponed", Json.array(slugs, Json::string)));
    }

    /** The reason on the marker, or empty when there is no marker to read one off. */
    private String reason(boolean postponed, String slug) {
        return postponed ? Objects.toString(sweep.postponedReason(slug), "no reason recorded") : "";
    }

    /**
     * WHY A ONE LINE FILE COULD NOT BE WRITTEN, in terms of the thing that is actually wrong.
     *
     * <p>MEASURED ON THE HOST, not reasoned about. The supervisor writes these markers as root from
     * inside a container, so when it creates {@code results/postponed} first the directory is
     * root-owned and mode 755, and this server, which runs as uid 1000, can then neither add a
     * marker to it nor unlink the supervisor's. Both arrive as an AccessDeniedException carrying
     * only the path, which on its own reads like a missing file.
     *
     * <p>The asymmetry does not arise the other way round: when this server creates the directory
     * it owns it, root can still write markers into it, and unlinking a root-owned marker from a
     * directory you own is permitted, since removal is a write to the directory. So the state to
     * warn about is specifically a postponed directory this server did not make.
     *
     * <p>There is no fallback to offer. run.sh clears these through a container for this same
     * reason, and this server deliberately holds no docker socket, so the honest reply is what
     * failed and who would have to undo it.
     *
     * <p>Visible to its test rather than private, because the build itself runs as root inside a
     * container and root cannot be refused a write, so the only way to exercise this on the sweep
     * host is to hand it the exception the host really produces.
     */
    String explain(IOException couldNotWrite, boolean wanted) {
        String what = wanted ? "could not set that bump aside" : "could not bring that bump back";
        if (couldNotWrite instanceof AccessDeniedException) {
            return what + ": " + sweep.postponedDir() + " belongs to another user, which on this"
                    + " host means the supervisor made it as root from inside a container. This"
                    + " server cannot write there and has no socket to borrow.";
        }
        return what + ": " + couldNotWrite;
    }

    private String answer(String slug, boolean now, boolean was, String why, String error) {
        List<String> fields = new ArrayList<>();
        fields.add(Json.field("slug", Json.string(slug)));
        fields.add(Json.field("postponed", String.valueOf(now)));
        fields.add(Json.field("was", String.valueOf(was)));
        fields.add(Json.field("changed", String.valueOf(now != was)));
        fields.add(Json.field("why", Json.string(why)));
        if (!slug.isEmpty()) {
            // THE PATH IT WROTE, because the only way to be sure a button works is to compare the
            // file it made against the file run.sh looks for, and a reply that names it turns that
            // check into one look at the response.
            fields.add(Json.field("marker",
                    Json.string(sweep.postponedDir().resolve(slug).toString())));
        }
        if (!error.isEmpty()) {
            fields.add(Json.field("error", Json.string(error)));
        }
        return Json.object(fields.toArray(String[]::new));
    }
}
