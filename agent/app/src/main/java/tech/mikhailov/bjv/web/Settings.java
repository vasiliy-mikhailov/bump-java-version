package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;

import tech.mikhailov.bjv.bump.Agents;
import tech.mikhailov.bjv.bump.Bom;
import tech.mikhailov.bjv.bump.Bump;
import tech.mikhailov.bjv.bump.Definition;
import tech.mikhailov.bjv.bump.Hop;
import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.config.Prompts;
import tech.mikhailov.ratchet.flow.Shape;

/**
 * WHAT A READER MAY CHANGE, AND WHAT THEY MAY ONLY SEE.
 *
 * <p>Four surfaces share this page and they are not equally powerful. The prompts and the bills of
 * materials are EDITED here, into a store beside the results, and a bump reads that store rather
 * than the code's own text. The lanes are edited too, because {@code run.sh} re-reads them every
 * round. The model key is edited too, into the run root, on a decision recorded at {@link #model}
 * that reverses what this page used to do. The model endpoint, the repository URL and the cache
 * locations are shown and nothing more: a supply chain a web page can redirect is a supply chain a
 * web page can redirect.
 *
 * <p>THE EDITS ARE PART OF THE PIPELINE'S IDENTITY. They live outside the image, so a commit and an
 * image hash together still do not say which prompts a run used; see Version, and the two hashes
 * every settled row carries.
 */
final class Settings {

    /**
     * WHERE THE MODEL KEY IS KEPT: beside {@code max_lanes}, the one other thing this page writes
     * into the run root. The run root is this container's only mount, and it is outside the git
     * work tree, which is what makes it the only place a credential typed here may land.
     */
    private static final String KEY_FILE = "model_key";

    /** Nobody but the user the sweep and this page both run as. */
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Results results;

    Settings(Path results) {
        this.results = new Results(results);
    }

    /** Every prompt for one hop, in the order the chain reaches them. */
    String agents(String hop) {
        Hop h = hopOf(hop);
        Map<String, String> stageOf = new LinkedHashMap<>();
        Map<String, String> roleOf = new LinkedHashMap<>();
        // THE CHAIN IS A PROGRAM AND IT HAS BLOCKS. modules and the repair campaign each run a
        // sub-chain between their planner and their verifier, and a flat list of agents says
        // nothing about that: it reads as a row of stages rather than two blocks with bodies. The
        // nesting is the tree's own now, walked rather than declared.
        Map<String, String> withinOf = new LinkedHashMap<>();
        // What the loop itself is, so the body can be shown between a header and a closer rather
        // than merely indented under a name.
        Map<String, String> loopOf = new LinkedHashMap<>();
        // How often a stage runs, which nesting does not say and a reader will otherwise guess.
        Map<String, String> repeatsOf = new LinkedHashMap<>();
        // Which bill of materials the stage works from, so the two pages connect.
        Map<String, String> readsOf = new LinkedHashMap<>();
        List<Shape.Stage> shape = Bump.stages();
        for (Shape.Stage s : shape) {
            for (Shape.Step step : s.steps()) {
                stageOf.put(step.name(), s.title());
                roleOf.put(step.name(), step.role());
                withinOf.put(step.name(), s.within());
                repeatsOf.put(step.name(), s.repeats());
                readsOf.put(step.name(), s.reads());
                if (!step.agent()) {
                    loopOf.put(s.title(), step.name());
                }
            }
        }
        Path root = results.root().resolve("prompts");
        // IN THE ORDER THE BUMP REACHES THEM, WHICH IS NOT THE ORDER THE FACTORY HANDS THEM BACK.
        // Measured on the live page: after-pins arrived after the repair agents, and
        // modules-verifier after that, so the settings page drew the module block missing its third
        // pass and the loop closing in the wrong place. The order is the tree's, walked above; the
        // factory's order is an accident of how the methods happen to be listed, and nothing should
        // depend on it.
        List<String> order = Shape.agentNames(shape);
        List<Definition> defined = new ArrayList<>(Agents.forHop(h, results.dir()));
        // ON THE STEM, BECAUSE THE TREE CANNOT NAME A PLATFORM. Fourteen of these agents exist once
        // per platform and are named before-pins-planner@spring-boot and so on, while the shape is
        // walked before any module has been looked at and can only ever say before-pins-planner.
        // Sorting on the full name would send all forty-two of them to the end of the page, in the
        // order the factory happens to build them, which is the failure the sort exists to prevent.
        // The sort is stable, so one agent's three platforms stay in the order definitions() lists
        // them.
        defined.sort(Comparator.comparingInt(d -> {
            int at = order.indexOf(Agents.stem(d.name()));
            // An agent the chain does not name goes last rather than first, so a new one shows up
            // as obviously unplaced instead of quietly heading the list.
            return at < 0 ? order.size() : at;
        }));
        return Json.array(defined, d -> {
            // BOTH TEXTS TRAVEL. The page cannot offer a revert without something to revert TO, and
            // a reader comparing an edit to the built-in should not have to redeploy to see it.
            boolean edited = Prompts.edited(root, d.name(), h.key());
            // WHERE IT SITS IS THE STEM'S, WHAT IT SAYS IS ITS OWN. The three platforms of one
            // agent run in the same stage, in the same role, as often as each other; what differs
            // is the text, and an edit is stored against the full name because the three are edited
            // apart.
            String stem = Agents.stem(d.name());
            return Json.object(
                    Json.field("name", Json.string(d.name())),
                    Json.field("role", Json.string(roleOf.getOrDefault(stem, "doer"))),
                    Json.field("stage", Json.string(stageOf.getOrDefault(stem, ""))),
                    // The stage this one runs inside, empty at the top level.
                    Json.field("within", Json.string(withinOf.getOrDefault(stem, ""))),
                    Json.field("repeats", Json.string(repeatsOf.getOrDefault(stem, ""))),
                    Json.field("reads", Json.string(readsOf.getOrDefault(stem, ""))),
                    // HOW MANY ROWS THAT LIST ACTUALLY HAS, counted from the file rather than
                    // written down beside it. A number typed into a page is a number that goes
                    // stale the first time somebody edits the list it describes.
                    Json.field("pins", String.valueOf(
                            readsOf.getOrDefault(stem, "").isEmpty() ? 0
                                    : Bom.of(h, readsOf.get(stem)).size())),
                    // The deterministic step that IS the loop, on the stage that owns one.
                    Json.field("loop", Json.string(
                            loopOf.getOrDefault(stageOf.getOrDefault(stem, ""), ""))),
                    Json.field("description", Json.string(d.description())),
                    Json.field("builtIn", Json.string(d.systemPrompt())),
                    Json.field("edited", String.valueOf(edited)),
                    Json.field("prompt", Json.string(
                            edited ? Prompts.override(d.name(), h.key()) : d.systemPrompt())));
        });
    }

    /**
     * SAVE OR REVERT ONE PROMPT.
     *
     * <p>An edit replaces the built-in entirely; there is no merge, because a prompt half from the
     * code and half from a box is a prompt nobody can read in one place — and reading it in one
     * place is how anybody works out why an agent did what it did.
     *
     * <p>The reply is the state AFTER the write, read back from the store, so the page shows what
     * landed rather than what was sent.
     */
    void prompt(HttpExchange x) throws IOException {
        Path root = results.root().resolve("prompts");
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = field(body, "name");
        Hop hop = hopOf(field(body, "hop"));
        boolean reverting = body.contains("\"revert\"") && body.contains("true");

        if (name.isBlank()) {
            Zone.send(x, 400, "application/json; charset=utf-8",
                    Json.object(Json.field("why", Json.string("no agent named")))
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (reverting) {
            Prompts.revert(root, name, hop.key());
        } else {
            String text = field(body, "text");
            if (text.isBlank()) {
                // A SAVE OF NOTHING IS A REVERT SPELLED WRONG. An agent handed an empty prompt does
                // something arbitrary, and the reader who cleared the box meant "use the built-in".
                Zone.send(x, 400, "application/json; charset=utf-8",
                        Json.object(Json.field("why", Json.string(
                                "an empty prompt is not a prompt; use revert to go back to the "
                                        + "code's own")))
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            Prompts.save(root, name, hop.key(), text);
        }
        Zone.json(x, Json.object(
                Json.field("name", Json.string(name)),
                Json.field("edited", String.valueOf(Prompts.edited(root, name, hop.key()))),
                Json.field("prompt", Json.string(Prompts.edited(root, name, hop.key())
                        ? Prompts.override(name, hop.key()) : ""))));
    }

    /**
     * READ AND WRITE ONE HOP'S LIST AS A FILE, which is what it is.
     *
     * <p>The rows go out parsed for the table; this goes out raw, because the thing being edited is
     * a file whose comments are half of what it says, and a round trip through records and back
     * would quietly drop them.
     *
     * <p>A save that cannot be parsed is refused here rather than accepted and thrown at the next
     * bump to discover. Loading throws on a malformed row on purpose, and that throw would land
     * inside a lane rather than on the page of whoever typed it.
     */
    void bom(HttpExchange x) throws IOException {
        String hop = Zone.param(x, "hop");
        String part = Zone.param(x, "part");
        if (!hop.matches("\\d+-\\d+")) {
            Zone.json(x, Json.object(Json.field("saved", "false"),
                    Json.field("why", Json.string("that is not a hop"))));
            return;
        }
        if (x.getRequestMethod().equalsIgnoreCase("GET")) {
            // BOTH HALVES IN ONE ANSWER. They are read together and edited together, and a page
            // that had to ask twice would show one of them stale for a moment.
            Hop asked = hopOf(hop);
            Zone.json(x, Json.object(
                        Json.field("hop", Json.string(asked.from() + " → " + asked.to())),
                        Json.field("name", Json.string(hop)),
                        Json.field("files", Json.array(Bom.parts(), name -> {
                            Bom.Source source = Bom.textFor(asked, name);
                            return Json.object(
                                    Json.field("part", Json.string(name)),
                                    Json.field("title", Json.string(name.equals("hardens")
                                            ? "what hardens the result"
                                            : "what enables the bump")),
                                    Json.field("about", Json.string(name.equals("hardens")
                                            ? "Polish on a project that already builds and tests "
                                            + "green at the target, where the patch releases carry "
                                            + "the CVE fixes. None of it can be raised until the "
                                            + "JDK has moved, and none of it is load-bearing for "
                                            + "the move."
                                            : "What makes the bump possible at all. A Lombok that "
                                            + "cannot read the new class file kills javac before "
                                            + "anything else runs. Below one of these the bump "
                                            + "does not happen, so they move before the JDK does.")),
                                    Json.field("rows",
                                            String.valueOf(Bom.of(asked, name).size())),
                                    Json.field("text", Json.string(source.text())),
                                    Json.field("edited", String.valueOf(source.edited())));
                    }))));
            return;
        }
        if (!Bom.parts().contains(part)) {
            Zone.json(x, Json.object(Json.field("saved", "false"),
                    Json.field("why", Json.string("a save names which half it is"))));
            return;
        }
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String key = Bom.key(hopOf(hop), part);
        try {
            if (body.isBlank()) {
                // AN EMPTY SAVE IS A REVERT, said once rather than needing its own button: the
                // built-in was never gone, so throwing the edit away restores it.
                Bom.revert(key);
            } else {
                Bom.save(key, body);
            }
            Bom.Source now = Bom.textFor(key);
            Zone.json(x, Json.object(Json.field("saved", "true"),
                    Json.field("edited", String.valueOf(now.edited())),
                    Json.field("rows", String.valueOf(Bom.of(hopOf(hop), part).size()))));
        } catch (IOException | RuntimeException refused) {
            Zone.json(x, Json.object(Json.field("saved", "false"),
                    Json.field("why", Json.string(String.valueOf(refused.getMessage())))));
        }
    }

    /**
     * HOW MANY BUMPS RUN AT ONCE, and the one setting on this page that is genuinely live.
     *
     * <p>{@code run.sh} re-reads {@code max_lanes} at the top of every round rather than at launch,
     * so a sweep starving the GPU can be throttled without stopping it. That is what makes this
     * writable when nothing else here is: the mechanism already existed and was reachable only by
     * someone with a shell on the box.
     *
     * <p>THE SERVER CLAMPS, and the reply is what it kept rather than what was sent. A page that
     * echoed the request would show 40 lanes on a box that will run 16, and the reader would not
     * find out until the sweep did not speed up.
     */
    void run(HttpExchange x) throws IOException {
        Path lanes = results.root().resolve("max_lanes");
        if ("POST".equalsIgnoreCase(x.getRequestMethod())) {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"lanes\"\\s*:\\s*(\\d+)").matcher(body);
            if (m.find()) {
                int kept = Math.max(1, Math.min(16, Integer.parseInt(m.group(1))));
                Files.writeString(lanes, kept + "\n");
            }
        }
        String now = Files.isRegularFile(lanes)
                ? Files.readString(lanes).trim() : "";
        Zone.json(x, Json.object(
                Json.field("lanes", now.matches("\\d+") ? now : "null"),
                Json.field("min", "1"),
                Json.field("max", "16"),
                Json.field("turns", Json.string(envOr("BJV_MODULE_TURNS", "3"))),
                // THE CEILING THAT ACTUALLY BINDS, and it was served nowhere. Turns and steps are
                // per module; this is the whole bump's allowance, and it is what stops a
                // twenty-module repository ordering seven hundred repair steps.
                Json.field("repairBudget", Json.string(envOr("BJV_REPAIR_BUDGET", "192"))),
                Json.field("steps", Json.string(envOr("BJV_STEPS", "6"))),
                Json.field("hangGuardMinutes", Json.string(envOr("BJV_HANG_GUARD", ""))),
                // WHERE DEPENDENCIES COME FROM, ON A PAGE, because until now it was knowable only
                // by reading settings.xml on the host. Maven learns the mirror from that file and
                // never needed it named anywhere else; Gradle cannot read a Maven settings file, so
                // the moment a recipe has to run under Gradle the URL has to exist as configuration
                // rather than as a line inside a file handed to one build tool.
                //
                // Read-only here, like the model endpoint beside it. A repository URL that a web
                // page can rewrite is a supply chain a web page can redirect.
                Json.field("repository", Json.string(envOr("BJV_REPO_URL", ""))),
                Json.field("mavenSettings", Json.string(envOr("BJV_SETTINGS", ""))),
                Json.field("mavenCache", Json.string(envOr("BJV_M2", ""))),
                Json.field("gradleCache", Json.string(envOr("BJV_GRADLE_RO", ""))),
                Json.field("gradleDists", Json.string(envOr("BJV_GRADLE_DISTS", ""))),
                Json.field("offline", String.valueOf(!envOr("BJV_SETTINGS", "").isBlank()))));
    }

    /**
     * THE ENDPOINT, AND NOW THE KEY WITH IT, WHICH REVERSES WHAT THIS METHOD USED TO SAY.
     *
     * <p>WHAT STOOD HERE IS KEPT, because a reader in six months needs to know the trade was
     * considered and overridden rather than never thought about. It read: the sibling tool renders
     * its API key into this page, with the reveal and copy buttons that cannot work otherwise, and
     * its own mount contract calls that out as the part a shell author has to read twice:
     * defensible for one person behind their own proxy, not on a portal several developers reach.
     * This tool is the second one mounted, so it takes the other side of that trade, whether a key
     * is SET travels, and the key never does. There is no reveal button because there is nothing
     * behind it.
     *
     * <p>THE OWNER REVERSED THAT, KNOWING WHAT IT COSTS. The key is readable and editable here now,
     * with the reveal and copy buttons the sibling has, which means it travels to every browser
     * that opens this page and sits in whatever that browser keeps. What protects it is one
     * password: the page answers at bump-java-version.mikhailov.tech behind Caddy's basic_auth and
     * behind nothing else, so everybody who has that password now has the key, and rotating the
     * password is no longer the same act as rotating the key.
     *
     * <p>The endpoint and the repository URL beside it stay read-only, on their own reasoning,
     * which this does not touch. A key is a credential the reader already owns; a repository URL is
     * where the code comes from, and a supply chain a web page can redirect is a supply chain a web
     * page can redirect.
     *
     * <p>WHERE IT LIVES: beside {@code max_lanes} in the run root, owner-only, staged and renamed
     * the way the corpus credentials are. That is not a preference. This container's one mount is
     * the run root, and {@code agent/.env} is outside it: a page on the public internet with write
     * access to the source tree would be a worse problem than the one this solves, so the mount
     * stays as narrow as it is and the key goes where the page can already write.
     *
     * <p>THE FILE WINS THE DISPLAY AND THE ENVIRONMENT WINS THE PROCESS, and saying which is which
     * is the whole of being honest here. The file is the most recent deliberate statement of what
     * the key should be, so it is what the page shows. The environment is what THIS process was
     * handed at deploy, and it is what the supervisor's own model calls keep using until the
     * container restarts. {@code run.sh} reads its key once at startup and hands it to each lane on
     * the command line, so a sweep already running keeps the key its launcher started with whatever
     * is saved here. That is not a footnote: the key was rotated in all three env files an hour
     * before this was written, this page picked it up on deploy, and every lane carried on with the
     * old one. The page says so in a sentence, because a control that reports success while the
     * thing it names carries on unchanged is the failure this codebase keeps finding.
     *
     * <p>NO LABEL COMES BACK, AND THAT IS A LIMIT RATHER THAN A CHOICE. The confirmation worth
     * having after a save would be the metering proxy's own name for the key, "now metering as
     * bump-java". That mapping lives in the {@code meterproxy} container's process environment; the
     * proxy publishes labels on {@code /metrics} and {@code /data.json} and never the mapping,
     * every other path it serves is forwarded upstream, and reading a container's environment needs
     * the docker socket this one deliberately does not have. Asking with the key would prove that
     * some request succeeded and would send the credential outward to find that out, which is a
     * check invented to have a check. So the reply says what landed and stops there.
     */
    void model(HttpExchange x) throws IOException {
        Path stored = results.root().resolve(KEY_FILE);
        boolean saved = false;
        String why = "";
        if ("POST".equalsIgnoreCase(x.getRequestMethod())) {
            String offered = field(new String(x.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8), "key").trim();
            why = whyThatIsNotAKey(offered);
            if (why.isEmpty()) {
                store(stored, offered);
                saved = true;
            }
        }
        // A REFUSAL COMES BACK AT 200 WITH ITS REASON, the way a refused bill of materials does.
        // read() in the client throws away the body of anything that is not 2xx, so a 400 reaches
        // the reader as "/api/settings/model answered 400" and the one sentence they need, that
        // what they pasted is eight characters long, is lost. The reason never quotes what was
        // sent: an error message is the easiest thing on a page to screenshot.
        String held = Files.isRegularFile(stored) ? Files.readString(stored).trim() : "";
        String launched = System.getenv().getOrDefault("OC_KEY", "");
        String key = held.isBlank() ? launched : held;
        Zone.json(x, Json.object(
                Json.field("saved", String.valueOf(saved)),
                Json.field("why", Json.string(why)),
                // THE KEY ITSELF. See above for the decision that put it here.
                Json.field("key", Json.string(key)),
                // KEPT, BECAUSE THE PILL OVER THE FIELD STILL ASKS ONLY THIS. A page that renders
                // the key has not stopped needing the one-word answer for when there is none, and
                // the shared component that draws it takes a boolean.
                Json.field("keySet", String.valueOf(!key.isBlank())),
                Json.field("keySource", Json.string(key.isBlank() ? ""
                        : held.isBlank() ? "the environment" : "this page")),
                // NAMED, NOT PATHED. The reader who wants to drop a key saved here and fall back to
                // the environment's does it with a shell, and the run root is a different path
                // inside this container from the one they will type on the host.
                Json.field("storedIn", Json.string(KEY_FILE)),
                Json.field("storedAt", String.valueOf(
                        held.isBlank() ? 0L : Files.getLastModifiedTime(stored).toMillis())),
                // WHETHER WHAT IS ON SCREEN IS WHAT ANYTHING IS ACTUALLY USING. False means this
                // process was started with the key it is showing; true means the key was saved
                // after the last deploy, and neither the supervisor here nor a lane out there has
                // it yet.
                Json.field("differsFromLaunch", String.valueOf(!key.equals(launched))),
                // NO DEFAULT. These were the author's own endpoint, the same pins removed from
                // Model, and a page that invents a plausible value for unset configuration is a
                // page that hides a broken deployment.
                Json.field("model", Json.string(envOr("OC_MODEL", ""))),
                Json.field("endpoint", Json.string(envOr("OC_BASE", ""))),
                Json.field("patienceMinutes", Json.string(envOr("BJV_PATIENCE_MINUTES", "240")))));
    }

    /**
     * WHY A VALUE IS REFUSED, IN WORDS THAT DO NOT REPEAT IT.
     *
     * <p>A settings page that can empty the key is a settings page that can stop the next sweep
     * without saying so, so blank is refused rather than stored. The sibling takes the other route
     * and leaves a blank box alone, which is defensible where a checkbox exists to drop the saved
     * key; there is no such checkbox here, and a save that silently did nothing is its own lie.
     *
     * <p>The rest is shape. A key from this endpoint is one run of printable characters, so what
     * these catch is a paste accident: half a key, a whole line of shell, a wrapped file.
     */
    private static String whyThatIsNotAKey(String offered) {
        if (offered.isBlank()) {
            return "an empty box is not a key, and storing one would stop the next sweep without "
                    + "saying anything";
        }
        if (offered.length() < 20) {
            return "that is shorter than any key this endpoint issues";
        }
        if (offered.length() > 200) {
            return "that is longer than a key, so something else came with it";
        }
        for (int i = 0; i < offered.length(); i++) {
            char c = offered.charAt(i);
            if (c < '!' || c > '~') {
                return "a key is one run of printable characters, with no spaces or line breaks "
                        + "anywhere in it";
            }
        }
        return "";
    }

    /**
     * OWNER-ONLY BEFORE IT HOLDS ANYTHING, THEN RENAMED OVER.
     *
     * <p>Registry writes the corpus credentials the same way and sets the mode afterwards. Here the
     * mode is a creation attribute instead, because the window between a world-readable create and
     * the chmod that closes it is a window on a bind mount, and this file is written by hand often
     * enough to be worth not having one.
     *
     * <p>The rename is for the same reason as every other write into the run root: a sweep holding
     * a file open must never see a truncated one. Nothing running reads this file, so the rename is
     * belt and braces rather than a fix, and it costs nothing.
     */
    private static void store(Path target, String key) throws IOException {
        Path staged = target.resolveSibling(target.getFileName() + ".staged");
        Files.deleteIfExists(staged);
        try {
            Files.createFile(staged, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException notPosix) {
            // A filesystem without POSIX modes is not a reason to refuse the save. The run root
            // here is an ext4 bind mount, so this branch exists for a test on a stranger's machine.
            Files.createFile(staged);
        }
        Files.writeString(staged, key + "\n", StandardCharsets.UTF_8);
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * UPLOAD A REGISTRY, AND BE TOLD WHAT IT DID.
     *
     * <p>The body is the file's text, not a multipart form: the page reads the file and posts what
     * is in it, so one path serves both the upload button and the paste box, and no multipart parser
     * has to exist in a container that already runs strangers' code.
     *
     * <p>WHAT COMES BACK IS WHAT LANDED. The count of rows accepted, the count actually new, and
     * every line that would not parse with its number and reason. A loader that answers "ok" to a
     * file half of which it discarded is the failure this is built against.
     *
     * <p>A GET says where it would go and whether a sweep is live, because "this takes effect on the
     * next round" and "this takes effect the next time somebody launches a sweep" are different
     * promises and the reader deserves to know which one they are getting.
     */
    void registry(HttpExchange x) throws IOException {
        Path root = results.root();
        Path active = root.resolve("active_manifest");
        Path target = null;
        if (Files.isRegularFile(active)) {
            // A BASENAME, RESOLVED AGAINST THE RUN ROOT. run.sh runs on the host and this runs in a
            // container that mounts the same directory somewhere else, so a path written by one
            // does not resolve for the other — and the failure is silent: the upload lands in a
            // file no sweep is reading and reports success.
            String name = Files.readString(active).trim();
            Path named = root.resolve(name.contains("/")
                    ? name.substring(name.lastIndexOf('/') + 1) : name);
            if (Files.isRegularFile(named)) {
                target = named;
            }
        }
        // No sweep running: the upload still has somewhere to go, and the next launch can be
        // pointed at it. Refusing the upload because nothing is running would mean a registry can
        // only be loaded onto a machine that is already busy.
        Path fallback = root.resolve("manifest.uploaded.tsv");

        if (!"POST".equalsIgnoreCase(x.getRequestMethod())) {
            Zone.json(x, Json.object(
                    Json.field("sweepLive", String.valueOf(target != null)),
                    Json.field("target", Json.string(
                            (target == null ? fallback : target).getFileName().toString()))));
            return;
        }

        String text = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Registry.Parsed parsed = Registry.parse(text);
        int added = 0;
        if (!parsed.empty()) {
            added = Registry.mergeInto(target == null ? fallback : target, parsed.rows());
            // Beside the manifest, never inside results/: that directory is what the page serves.
            Registry.recordOrigins(root.resolve("origins.tsv"), parsed.rows());
            Registry.recordKeys(root.resolve("credentials.tsv"), parsed.rows());
        }
        Zone.json(x, Json.object(
                Json.field("accepted", String.valueOf(parsed.rows().size())),
                Json.field("added", String.valueOf(added)),
                // COUNTS ONLY. No response here or anywhere carries a key's value.
                Json.field("keyed", String.valueOf(parsed.keyed())),
                Json.field("unpinned", String.valueOf(parsed.unpinned())),
                Json.field("sweepLive", String.valueOf(target != null)),
                Json.field("target", Json.string(
                        (target == null ? fallback : target).getFileName().toString())),
                Json.field("rejected", Json.array(parsed.rejected(), r -> Json.object(
                        Json.field("line", String.valueOf(r.line())),
                        Json.field("text", Json.string(r.text())),
                        Json.field("why", Json.string(r.why())))))));
    }

    /** The watcher that sees what one bump cannot, and what it has found. */
    String supervisor() {
        List<String> findings = Results.lines(results.dir().resolve("findings.jsonl"));
        List<String> postponed = Results.lines(results.dir().resolve("postponed"));
        return Json.object(
                Json.field("everyMinutes", Json.string(envOr("BJV_SUPERVISOR_MINUTES", "20"))),
                Json.field("findings", String.valueOf(findings.size())),
                Json.field("postponed", String.valueOf(postponed.size())),
                Json.field("latest", Json.array(
                        findings.subList(Math.max(0, findings.size() - 8), findings.size()),
                        line -> {
                            Map<String, String> r = Json.row(line);
                            return Json.object(
                                    Json.field("at", String.valueOf(Results.num(r.get("at")))),
                                    Json.field("bump", Json.string(r.getOrDefault("bump", ""))),
                                    Json.field("kind", Json.string(r.getOrDefault("kind", ""))),
                                    Json.field("what", Json.string(Results.first(r, "what", "note"))),
                                    Json.field("held",
                                            String.valueOf("true".equals(r.get("held")))));
                        })));
    }

    /** One JSON string field out of a small body. The bodies here are three fields deep. */
    private static String field(String body, String name) {
        Matcher m = Pattern
                .compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        if (!m.find()) {
            return "";
        }
        return m.group(1).replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\r", "\r").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Hop hopOf(String hop) {
        String[] pair = (hop.isBlank() ? "17-21" : hop).split("-");
        return new Hop(Integer.parseInt(pair[0]),
                Integer.parseInt(pair.length > 1 ? pair[1] : pair[0]));
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv().getOrDefault(name, "");
        return v.isBlank() ? fallback : v;
    }
}
