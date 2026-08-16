package tech.mikhailov.bjv.agent;

import java.io.IOException;
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

    /** Parsed once per rung. The files do not change under a running sweep. */
    private static final Map<Integer, List<Floor>> LOADED = new LinkedHashMap<>();

    private Bom() {
    }

    /**
     * The floors for one target, read from the bill of materials for its rung.
     *
     * @throws IllegalStateException if the file is missing or any row cannot be read, because a
     *                               floor that silently vanishes from this list is the exact
     *                               failure the class doc describes
     */
    static synchronized List<Floor> of(int target) {
        return LOADED.computeIfAbsent(rung(target), Bom::load);
    }

    /** Which list a target reads, on the same ladder {@link Floors#forTarget} walks. */
    static int rung(int target) {
        return target >= 25 ? 25 : target >= 21 ? 21 : target >= 17 ? 17 : 11;
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
    private static List<Floor> load(int rung) {
        String text;
        try (var in = Bom.class.getResourceAsStream("/bom/" + rung + ".tsv")) {
            if (in == null) {
                throw new IllegalStateException("no bill of materials for target " + rung);
            }
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException("could not read the bill of materials for " + rung,
                    unreadable);
        }
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
                        "target " + rung + " has a row this cannot read: " + raw);
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
            throw new IllegalStateException("the bill of materials for " + rung + " is empty");
        }
        return List.copyOf(floors);
    }

    /**
     * Score a workspace against its target's floors.
     *
     * @param declared what each artifact is declared at, keyed by coordinate, lowest already chosen
     * @param build    {@code maven}, {@code gradle} or {@code both}, from {@link Migrate#actuatorFor}
     */
    static Compliance against(int target, Map<String, String> resolved,
                              Map<String, String> declared, String build) {
        List<Verdict> verdicts = new ArrayList<>();
        int met = 0;
        int missed = 0;
        for (Floor floor : of(target)) {
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
        Map<String, String> lowest = new LinkedHashMap<>();
        if (!java.nio.file.Files.isRegularFile(trace)) {
            return lowest;
        }
        String latest = "";
        try {
            for (String line : java.nio.file.Files.readAllLines(trace,
                    java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.contains("\"stage\":\"packages-after\"")) {
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
    static void record(Path results, String bump, Compliance c) {
        String outstanding = c.outstanding().stream()
                .map(v -> v.floor().artifact() + " " + v.declared() + " below " + v.floor().version())
                .reduce((a, b) -> a + "; " + b).orElse("");
        String line = "{\"bump\":\"" + Settlement.escape(bump) + "\",\"met\":" + c.met()
                + ",\"missed\":" + c.missed() + ",\"percent\":" + c.percent()
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
    static Compliance measure(Path ws, Path trace, int target) {
        try {
            return against(target, resolvedIn(trace), declaredIn(ws), Migrate.actuatorFor(ws));
        } catch (IOException | RuntimeException unreadable) {
            return new Compliance(List.of(), 0, 0);
        }
    }
}
