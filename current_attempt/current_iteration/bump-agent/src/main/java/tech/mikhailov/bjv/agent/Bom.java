package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * THE FLOORS FOR ONE TARGET, AS DATA, SO A FINISHED BUMP CAN BE SCORED AGAINST THEM.
 *
 * <p>A green gate says the project builds under the target JDK and lost no test. It says nothing
 * about whether the project reached the versions the target actually needs, and those are different
 * questions: a repository can pass while sitting on a Lombok that only survives because nothing
 * exercised it, or on a Spring Boot twelve patch releases behind its own line. This turns the second
 * question into a number.
 *
 * <p>HAND MAINTAINED, AND CHECKED AGAINST THE PROSE RATHER THAN PARSED OUT OF IT. {@link Floors}
 * states the same facts as prose because that is what a planner reads, and a planner reads prose
 * better than a table. This is the other half of the same fact in the shape a program can act on.
 *
 * <p>Parsing the prose was the obvious way to avoid two lists and is exactly the thing this file
 * must not do. A positional split over those lines once turned each into a record, and those
 * records decided whether an agent was shown the list at all: it looked for an artifact no project
 * declares, called every floor met, and skipped the phase silently for the whole corpus. Two
 * readers of one string, and the quiet one won.
 *
 * <p>So there are two lists and they are made to agree out loud. A test walks both and fails the
 * build the moment a coordinate or a version disagrees, which is a drift that shows up in seconds
 * rather than a parse that degrades in silence.
 *
 * <p>Today nothing acts on this; it is measured against, and a wrong number is a wrong number. It
 * is meant to become what a deterministic pinning step applies, at which point the prose stops
 * carrying versions and starts carrying only the reasons.
 */
final class Bom {

    /**
     * One floor: what it is, how low it may be, and every way a build file can spell it.
     *
     * @param coordinates the canonical {@code group:artifact}, as {@link Floors} writes it
     * @param version     the lowest acceptable version
     * @param phase       {@code before} or {@code after}, meaning which side of the JDK change
     * @param spellings   every name a build file may use for this same artifact, canonical included
     * @param dialect     {@code maven}, {@code gradle}, or {@code any}: which build systems can have
     *                    this at all
     */
    record Floor(String coordinates, String version, String phase, Set<String> spellings,
                 String dialect) {

        /** The short name a table column can carry. */
        String artifact() {
            int colon = coordinates.indexOf(':');
            return colon < 0 ? coordinates : coordinates.substring(colon + 1);
        }
    }

    /** How one floor came out against one repository. */
    record Verdict(Floor floor, String declared, boolean applicable, boolean met) {
    }

    /** The whole answer for one bump, and the number that goes in the column. */
    record Compliance(List<Verdict> verdicts, int met, int missed) {

        int applicable() {
            return met + missed;
        }

        /**
         * Percentage met, or -1 when nothing applied.
         *
         * <p>MINUS ONE RATHER THAN ZERO. A repository that declares none of these floors has not
         * failed them, and rendering that as 0 per cent puts it below a repository that met half.
         * An absent measurement read as a measurement of zero is the most expensive recurring bug
         * in this corpus, and a percentage is where it hides best.
         */
        int percent() {
            return applicable() == 0 ? -1 : met * 100 / applicable();
        }

        /** The floors a passing repository still sits below, worst first is not knowable, so in list order. */
        List<Verdict> outstanding() {
            return verdicts.stream().filter(v -> v.applicable() && !v.met()).toList();
        }
    }

    /**
     * WHAT MOVED, OVER THE FLOORS THAT WERE JUDGEABLE ON BOTH SIDES.
     *
     * <p>The two compliances are not comparable as totals and it is not close. The after-scan only
     * runs on a green gate, so a bump that never reached one has a resolved tree before and none
     * after, and its "after" is smaller because less was measured rather than because anything was
     * fixed. Measured on this corpus: packages-before exists on 69 traces and packages-after on 39,
     * and repositories came out reading "was 3 of 11, now 0 of 4".
     *
     * <p>Subtracting those would report the missing measurement as work done, which is the most
     * expensive recurring mistake in this project wearing a rate. So a floor counts here only if it
     * applied on BOTH sides, and everything else is left out of the arithmetic entirely.
     */
    record Movement(int applied, int missedBefore, int missedAfter) {
    }

    /** The comparable half of two measurements of one repository. */
    static Movement between(Compliance was, Compliance now) {
        Map<String, Verdict> then = new LinkedHashMap<>();
        for (Verdict v : was.verdicts()) {
            if (v.applicable()) {
                then.put(v.floor().coordinates(), v);
            }
        }
        int applied = 0;
        int before = 0;
        int after = 0;
        for (Verdict v : now.verdicts()) {
            Verdict earlier = v.applicable() ? then.get(v.floor().coordinates()) : null;
            if (earlier == null) {
                continue;
            }
            applied++;
            if (!earlier.met()) {
                before++;
            }
            if (!v.met()) {
                after++;
            }
        }
        return new Movement(applied, before, after);
    }

    /**
     * AN EDITED LIST REPLACES THE BUILT-IN ENTIRELY, on the same terms as an edited prompt.
     *
     * <p>No merge. A list half from the code and half from a box is a list nobody can read in one
     * place, and reading it in one place is the only way anyone works out why a version was asked
     * for. The store sits beside the results rather than inside them, because results are a record
     * of what happened and this is an instruction about what should.
     */
    private static volatile Path store = null;

    /** Point the store at a run root, before anything reads a list. */
    static void beside(Path results) {
        Path root = results.getParent() == null ? results : results.getParent();
        store = root.resolve("bom");
    }

    static Path store() {
        return store;
    }

    /** Where one hop's edit lives, or null when there is nowhere to put it. */
    static Path fileFor(Path root, String hop) {
        if (root == null || !hop.matches("\\d+-\\d+")) {
            return null;
        }
        return root.resolve(hop + ".tsv");
    }

    /**
     * The text a hop's list is read from, and where it came from.
     *
     * <p>Serving the raw file rather than the parsed rows is deliberate: the thing being edited is
     * a file, the comments in it are half of what it says, and a round trip through records and
     * back would quietly drop them.
     */
    record Source(String text, boolean edited) {
    }

    static Source textFor(String hop) {
        Path file = fileFor(store, hop);
        if (file != null && Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                // AN EMPTY FILE IS NOT AN EMPTY LIST. It is a save that went wrong, and a bump
                // measured against nothing reads as perfectly compliant.
                if (!text.isBlank()) {
                    return new Source(text, true);
                }
            } catch (IOException unreadable) {
                // Fall through to the built-in, which is always there.
            }
        }
        return new Source(builtIn(hop), false);
    }

    private static String builtIn(String hop) {
        try (var in = Bom.class.getResourceAsStream("/bom/" + hop + ".tsv")) {
            return in == null ? ""
                    : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** Save an edit, staged and renamed over so no reader sees half of each. */
    static void save(String hop, String text) throws IOException {
        Path file = fileFor(store, hop);
        if (file == null) {
            throw new IOException("no bill-of-materials store configured");
        }
        if (text.isBlank()) {
            throw new IOException("an empty list would read as a project meeting everything");
        }
        // PARSED BEFORE IT IS KEPT. A file that cannot be read throws at load, and load happens
        // inside a bump; refusing here turns a sweep-wide failure into a message on a page.
        parse(hop, text);
        Files.createDirectories(file.getParent());
        Path staged = file.resolveSibling(file.getFileName() + ".staged");
        Files.writeString(staged, text, java.nio.charset.StandardCharsets.UTF_8);
        Files.move(staged, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Throw the edit away. The built-in is not restored; it was never gone. */
    static void revert(String hop) throws IOException {
        Path file = fileFor(store, hop);
        if (file != null) {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Parsed per read, not once.
     *
     * <p>The cache this replaces held a list for the life of the process, which is right for a file
     * baked into a jar and wrong the moment one can be edited from a page: the dashboard would have
     * gone on measuring against the version it started with and shown the new one.
     */

    private Bom() {
    }

    /**
     * The floors for one target, read from the bill of materials for its rung.
     *
     * @throws IllegalStateException if the file is missing or any row cannot be read, because a
     *                               floor that silently vanishes from this list is the exact
     *                               failure the class doc describes
     */
    static List<Floor> of(Hop hop) {
        return load(name(hop));
    }

    /**
     * WHICH FILE A HOP READS, and there is one per hop rather than one per target.
     *
     * <p>The phase column says "before the JDK" and "after the JDK", and neither means anything
     * without both ends of the move: what a project may raise before the JDK changes depends on the
     * JDK it is leaving as much as the one it is going to.
     *
     * <p>A hop that is not one of the four the corpus runs is answered by the nearest one at or
     * below its target, because a 17 to 19 move crosses the same walls a 17 to 21 move does.
     */
    static String name(Hop hop) {
        int to = hop.to();
        String rung = to >= 25 ? "21-25" : to >= 21 ? "17-21" : to >= 17 ? "11-17" : "8-11";
        String exact = hop.from() + "-" + hop.to();
        return Bom.class.getResource("/bom/" + exact + ".tsv") != null ? exact : rung;
    }

    /**
     * EVERY WAY ONE ARTIFACT CAN BE WRITTEN, and which build systems can write it at all.
     *
     * <p>Kept in the files rather than here. What follows documents why the column exists.
     *
     * <p>Without this the measurement is a lie on half the corpus. {@link Floors} names Spring Boot
     * as {@code spring-boot-starter-parent} and {@code spring-boot-dependencies}, and neither string
     * can appear in a Gradle build: Gradle says {@code id("org.springframework.boot") version "X"} or
     * puts {@code spring-boot-gradle-plugin} on the buildscript classpath. A Gradle project on Boot
     * 3.5.16 would read as failing a floor it had already met.
     *
     * <p>The pattern is not new here. {@code org.gradle:gradle-wrapper} is a synthetic coordinate
     * that {@link Declared} mints from a distributionUrl, and it has always worked precisely because
     * both sides agreed on one name for one fact. This gives the other rows the same treatment.
     *
     * <p>DIALECT IS NOT THE SAME AS NOT DECLARED. {@code maven-compiler-plugin} has no Gradle
     * analogue at all, so on a Gradle project that floor does not apply and must not count against
     * it; the same for the wrapper on a Maven project. Counting an impossible row as a miss is how a
     * percentage becomes an accusation.
     */
    private static List<Floor> load(String hop) {
        String text = textFor(hop).text();
        if (text.isEmpty()) {
            throw new IllegalStateException("no bill of materials for the " + hop + " hop");
        }
        return parse(hop, text);
    }

    private static List<Floor> parse(String hop, String text) {
        List<Floor> floors = new ArrayList<>();
        for (String raw : text.lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // STRICT, AND LOUD. A row this cannot read must not become a row that is not there:
            // a shorter list is a healthier looking percentage, which is the same failure the
            // class doc describes wearing different clothes.
            String[] cell = line.split("\\t", -1);
            if (cell.length < 4 || !cell[0].contains(":")
                    || !cell[1].matches("\\d[\\w.\\-]*")
                    || !Set.of("before", "after").contains(cell[2])
                    || !Set.of("any", "maven", "gradle").contains(cell[3])) {
                throw new IllegalStateException(
                        "the " + hop + " hop has a row this cannot read: " + raw);
            }
            Set<String> spellings = new java.util.LinkedHashSet<>();
            spellings.add(cell[0]);
            if (cell.length > 4) {
                for (String also : cell[4].split(",")) {
                    if (!also.isBlank()) {
                        spellings.add(also.strip());
                    }
                }
            }
            floors.add(new Floor(cell[0], cell[1], cell[2], spellings, cell[3]));
        }
        if (floors.isEmpty()) {
            throw new IllegalStateException("the bill of materials for " + hop + " is empty");
        }
        return List.copyOf(floors);
    }

    /**
     * Score a workspace against its target's floors.
     *
     * @param declared what each artifact is declared at, keyed by coordinate, lowest already chosen
     * @param build    {@code maven}, {@code gradle} or {@code both}, from {@link Migrate#actuatorFor}
     */
    static Compliance against(Hop hop, Map<String, String> resolved,
                              Map<String, String> declared, String build) {
        List<Verdict> verdicts = new ArrayList<>();
        int met = 0;
        int missed = 0;
        for (Floor floor : of(hop)) {
            if (!speaks(build, floor.dialect())) {
                verdicts.add(new Verdict(floor, "", false, false));
                continue;
            }
            // WHAT RESOLVED, WHERE THAT IS KNOWN. A build file is a request and the tree is the
            // answer, and they differ constantly: a Boot project declares no version for mockito
            // at all and runs whichever one the BOM manages, and a Gradle project can hold its
            // Boot version in an ext variable that no build-file reader will ever turn into a
            // number. The floor is a claim about what runs, so the resolved version settles it
            // and the declaration is the fallback for the rows that are never a dependency: a
            // wrapper, a maven plugin, a parent pom.
            String lowest = lowestOf(floor, resolved);
            if (lowest.isEmpty()) {
                lowest = lowestOf(floor, declared);
            }
            if (lowest.isEmpty()) {
                // NOT DECLARED IS NOT A MISS. A project that uses no archunit has not failed the
                // archunit floor, and counting it would make the denominator a measure of how long
                // the list is rather than of the project.
                verdicts.add(new Verdict(floor, "", false, false));
                continue;
            }
            // A ROW IS THE HEAD OF A LINE, AND ONLY ITS OWN LINE ANSWERS TO IT. Same major, same
            // minor, and this is a patch away; anything else is a different line and a different
            // move. tomcat-embed-core sits at 9.0.105 and at 10.1.55 in the same file for exactly
            // this reason, and a project on 9.0.65 must not read as failing the 10.1 row: crossing
            // from Tomcat 9 to Tomcat 10 is the jakarta rename, which is not something a patch does
            // and not something this should ever ask for by implication.
            if (!line(lowest).equals(line(floor.version()))) {
                verdicts.add(new Verdict(floor, "", false, false));
                continue;
            }
            boolean ok = Migrate.compare(lowest, floor.version()) >= 0;
            verdicts.add(new Verdict(floor, lowest, true, ok));
            if (ok) {
                met++;
            } else {
                missed++;
            }
        }
        return new Compliance(verdicts, met, missed);
    }

    /**
     * The lowest version any of a floor's spellings carries in one source.
     *
     * <p>LOWEST WINS ACROSS SPELLINGS AND MODULES. One module at the floor does not lift the
     * repository, and reading it the other way is what let this corpus report every pin met while
     * most of it sat below.
     *
     * <p>A spelling ending {@code :*} matches every artifact of that group, which is how a family
     * that ships forty jars at one version is recognised without listing forty names.
     */
    private static String lowestOf(Floor floor, Map<String, String> source) {
        String lowest = "";
        for (String spelling : floor.spellings()) {
            String key = spelling.toLowerCase(Locale.ROOT);
            if (key.endsWith(":*")) {
                String group = key.substring(0, key.length() - 1);
                for (Map.Entry<String, String> e : source.entrySet()) {
                    if (e.getKey().startsWith(group)) {
                        lowest = lower(lowest, e.getValue());
                    }
                }
                continue;
            }
            lowest = lower(lowest, source.get(key));
        }
        return lowest;
    }

    private static String lower(String held, String found) {
        if (found == null || found.isBlank() || !found.matches("\\d.*")) {
            return held;
        }
        return held.isEmpty() || Migrate.compare(found, held) < 0 ? found : held;
    }

    /**
     * WHAT ACTUALLY RESOLVED, read from the scan the bump already ran.
     *
     * <p>{@code packages-after} is the whole dependency tree as the build resolved it, one row per
     * module and artifact, and it is recorded on every bump that reached a green gate. It is a
     * better answer than any build file because it is the answer: versions a BOM manages, versions
     * behind a variable, and versions a parent supplies all arrive here as numbers.
     *
     * <p>Empty when the bump never reached a gate, which is not the same as a project with no
     * dependencies and must not be read as one.
     */
    static Map<String, String> resolvedIn(Path trace) {
        return resolvedIn(trace, "packages-after");
    }

    /** The same, for whichever scan is wanted: the tree before the bump, or the tree after it. */
    static Map<String, String> resolvedIn(Path trace, String stage) {
        Map<String, String> lowest = new LinkedHashMap<>();
        if (!java.nio.file.Files.isRegularFile(trace)) {
            return lowest;
        }
        String latest = "";
        try {
            for (String line : java.nio.file.Files.readAllLines(trace,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.contains("\"stage\":\"" + stage + "\"")) {
                    latest = line;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return lowest;
        }
        int at = latest.indexOf("\"what\":\"");
        if (at < 0) {
            return lowest;
        }
        String body = latest.substring(at + 8);
        // Rows are module TAB coordinate TAB version TAB cves, escaped into one json string.
        for (String row : body.split("\\\\n")) {
            String[] cell = row.split("\\\\t");
            if (cell.length >= 3 && cell[1].contains(":")) {
                String key = cell[1].toLowerCase(Locale.ROOT);
                lowest.put(key, lower(lowest.getOrDefault(key, ""), cell[2]));
            }
        }
        lowest.values().removeIf(String::isEmpty);
        return lowest;
    }

    /** The line a version sits on: major.minor, or the whole string when it has neither. */
    private static String line(String version) {
        String[] part = version.split("[.-]");
        return part.length >= 2 ? part[0] + "." + part[1] : version;
    }

    private static boolean speaks(String build, String dialect) {
        return dialect.equals("any") || build.equals("both") || build.equals(dialect);
    }

    /**
     * What a workspace declares, flattened to one lowest version per coordinate.
     *
     * <p>Reads the same build files {@link Declared} reads and in the same dialects, so a Gradle
     * plugins block and a Maven parent block both arrive here as a coordinate and a version.
     */
    static Map<String, String> declaredIn(Path ws) throws IOException {
        Map<String, String> lowest = new LinkedHashMap<>();
        List<Modules.Module> modules = Modules.of(ws);
        for (Modules.Module m : modules) {
            for (Path f : Modules.buildFilesOf(ws, m, modules)) {
                for (Declared.Version v : Declared.in(java.nio.file.Files.readString(f),
                        ws.relativize(f).toString())) {
                    if (!v.value().matches("\\d.*")) {
                        continue;
                    }
                    String key = v.coordinates().toLowerCase(Locale.ROOT);
                    String held = lowest.get(key);
                    if (held == null || Migrate.compare(v.value(), held) < 0) {
                        lowest.put(key, v.value());
                    }
                }
            }
        }
        return lowest;
    }

    /**
     * WRITTEN BESIDE THE SETTLEMENTS, NOT INTO THEM.
     *
     * <p>A live sweep is appending to settlements.jsonl and two readers take the last line per bump
     * to decide what state it is in. Adding a new kind of line to that file to carry a number would
     * put a measurement in the path of a verdict, which is a trade nobody should take. This is a
     * separate file with the same key, joined where it is read.
     *
     * <p>Append only, and one line per attempt rather than one per bump: a bump that is run again
     * against a changed harness should leave both numbers on the record, and the reader takes the
     * last.
     */
    static void record(Path results, String bump, Compliance c, Compliance was) {
        Movement moved = between(was, c);
        String outstanding = c.outstanding().stream()
                .map(v -> v.floor().artifact() + " " + v.declared() + " below " + v.floor().version())
                .reduce((a, b) -> a + "; " + b).orElse("");
        String line = "{\"bump\":\"" + Settlement.escape(bump) + "\",\"met\":" + c.met()
                + ",\"missed\":" + c.missed() + ",\"percent\":" + c.percent()
                + ",\"metBefore\":" + was.met() + ",\"missedBefore\":" + was.missed()
                // The comparable half, which is what a before-and-after rate may be built from.
                + ",\"pairApplied\":" + moved.applied()
                + ",\"pairMissedBefore\":" + moved.missedBefore()
                + ",\"pairMissedAfter\":" + moved.missedAfter()
                + ",\"outstanding\":\"" + Settlement.escape(outstanding) + "\"}\n";
        try {
            java.nio.file.Files.createDirectories(results);
            java.nio.file.Files.writeString(results.resolve("bom.jsonl"), line,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            // A measurement that cannot be filed is not worth failing a settled bump over.
        }
    }

    /** The whole measurement for one finished bump, or an empty compliance when unreadable. */
    static Compliance measure(Path ws, Path trace, Hop hop) {
        try {
            return against(hop, resolvedIn(trace), declaredIn(ws), Migrate.actuatorFor(ws));
        } catch (IOException | RuntimeException unreadable) {
            return new Compliance(List.of(), 0, 0);
        }
    }

    /**
     * WHERE THE PROJECT STOOD BEFORE ANY OF THIS TOUCHED IT.
     *
     * <p>Without it there is a compliance number and nothing to compare it against, and the
     * question everyone actually asks is not how compliant a repository is but how much of the
     * distance this harness closed. A project that arrived at the floor owes the bump nothing.
     *
     * <p>ON THE SAME BASIS AS THE AFTER, which is the whole point and the only difficult part. The
     * resolved half comes from the scan that ran before the bump; the declared half is read out of
     * git at the sha the corpus pinned, because rows that are never a dependency -- a wrapper, a
     * maven plugin, a parent pom -- exist only in build files. Measuring the before from resolved
     * packages alone and the after from both would report every one of those rows as an issue this
     * harness introduced.
     *
     * <p>The module list is the one on disk now. A bump that adds or removes a module would have
     * this read the new shape against old contents, which is wrong at the edges and much less wrong
     * than not reading the declarations at all.
     */
    static Compliance measureBefore(Path ws, Path trace, String sha, Hop hop) {
        try {
            return against(hop, resolvedIn(trace, "packages-before"), declaredAt(ws, sha),
                    Migrate.actuatorFor(ws));
        } catch (IOException | RuntimeException unreadable) {
            return new Compliance(List.of(), 0, 0);
        }
    }

    /** What the build files said at one commit, which is not what they say now. */
    static Map<String, String> declaredAt(Path ws, String sha) throws IOException {
        Map<String, String> lowest = new LinkedHashMap<>();
        if (sha == null || sha.isBlank()) {
            return lowest;
        }
        List<Modules.Module> modules = Modules.of(ws);
        for (Modules.Module m : modules) {
            for (Path f : Modules.buildFilesOf(ws, m, modules)) {
                String relative = ws.relativize(f).toString().replace('\\', '/');
                String text = show(ws, sha, relative);
                if (text.isEmpty()) {
                    continue;
                }
                for (Declared.Version v : Declared.in(text, relative)) {
                    if (v.value().matches("\\d.*")) {
                        String key = v.coordinates().toLowerCase(Locale.ROOT);
                        lowest.put(key, lower(lowest.getOrDefault(key, ""), v.value()));
                    }
                }
            }
        }
        lowest.values().removeIf(String::isEmpty);
        return lowest;
    }

    /**
     * One file as of one commit, or empty when it did not exist then.
     *
     * <p>Empty is a real answer and not an error: a build file this harness ADDED has no earlier
     * version, and treating that as a failure would lose every other file in the module.
     */
    private static String show(Path ws, String sha, String relative) {
        try {
            Shell.Output out = Shell.run(ws, Map.of(), java.time.Duration.ofSeconds(30),
                    "git", "-c", "safe.directory=" + ws, "show", sha + ":" + relative);
            return out.code() == 0 ? out.text() : "";
        } catch (IOException | InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
