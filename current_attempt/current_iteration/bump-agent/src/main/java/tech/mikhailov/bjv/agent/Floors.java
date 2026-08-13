package tech.mikhailov.bjv.agent;

import java.util.Comparator;
import java.util.List;

/**
 * THE VERSION A DEPENDENCY NEEDS TO SURVIVE A GIVEN TARGET, in one place.
 *
 * <p>Written twice before this: {@link Migrate} applies these floors proactively, before the first
 * build, and {@link Walls} applies the same ones reactively when a build fails with the signature
 * that names them. Both carried {@code target >= 25 ? "1.18.46" : "1.18.30"} verbatim, so raising a
 * floor in one left the other quietly disagreeing, and which version a project ended up with
 * depended on whether it happened to fail first.
 *
 * <p>KEYED ON THE TARGET, NOT THE HOP. A dependency does not care where a project came from, only
 * which JDK it must now run under, so there is no 8-to-11 entry and no 11-to-17 entry: there is a
 * version that works at 21 and a version that works at 25. Several floors have only one row, which
 * means they are version pins rather than hop knowledge, and saying so is more honest than dressing
 * them up as the latter.
 *
 * <p>Every version here was measured on this corpus rather than read off a compatibility table.
 */
final class Floors {

    /**
     * One rule: from {@code sinceTarget} upward, this artifact must be at least this version.
     *
     * <p>The reason travels with it because a floor without one is indistinguishable from a
     * superstition, and these accumulate.
     */
    record Floor(String group, String artifact, String version, int sinceTarget, String why) {

        String coordinates() {
            return group + ":" + artifact;
        }
    }

    private static final List<Floor> ALL = List.of(
            new Floor("org.projectlombok", "lombok", "1.18.30", 0,
                    "Older Lombok reads javac internals that moved; it dies with "
                            + "ExceptionInInitializerError on TypeTags, which never names Lombok."),
            new Floor("org.projectlombok", "lombok", "1.18.46", 25,
                    "The 1.18.30 line does not understand the JDK 25 AST either."),
            new Floor("net.bytebuddy", "byte-buddy", "1.14.12", 0,
                    "Byte Buddy refuses a class file major it does not know, which is what "
                            + "Mockito reports as being unable to mock a class."),
            new Floor("net.bytebuddy", "byte-buddy", "1.17.6", 25,
                    "The first line that knows class file 69."),
            new Floor("net.bytebuddy", "byte-buddy-agent", "1.14.12", 0,
                    "Moves with byte-buddy; a split pair fails in the same place."),
            new Floor("net.bytebuddy", "byte-buddy-agent", "1.17.6", 25,
                    "Moves with byte-buddy."),
            new Floor("org.mockito", "mockito-core", "5.18.0", 0,
                    "Carries the Byte Buddy floor transitively and drops the JDK 8 support that "
                            + "pins it back."),
            new Floor("com.tngtech.archunit", "archunit", "1.4.1", 0,
                    "Reads bytecode directly and rejects a major it predates."),
            new Floor("com.tngtech.archunit", "archunit-junit5", "1.4.1", 0,
                    "Moves with archunit."),
            new Floor("org.apache.tomcat.embed", "tomcat-embed-core", "9.0.105", 21,
                    "The newest 9.0 the mirror carries, and the fewest CVEs of that line. Only "
                            + "where Spring is absent: Boot 3.5 brings tomcat 10.1.55, which is "
                            + "newer, and pinning a 9.0.x under it is a downgrade across jakarta."),
            new Floor("org.jacoco", "jacoco-maven-plugin", "0.8.15", 0,
                    "JaCoCo instruments bytecode and refuses a class file major it predates."),
            new Floor("org.jetbrains.kotlin", "kotlin", "2.3.20", 25,
                    "Every Kotlin 1.x either crashes on JDK 25 or silently falls back below the "
                            + "target, which the gate then reads as an unraised bump."),
            new Floor("org.gradle", "gradle-wrapper", "7.6", 17,
                    "Older wrappers cannot run the toolchain the target needs. Applied by the "
                            + "preparer rather than here: a wrapper is not a dependencyManagement "
                            + "entry, and these floors are Maven-only."),
            new Floor("org.gradle", "gradle-wrapper", "8.10.2", 21, "As above, for target 21."),
            new Floor("org.gradle", "gradle-wrapper", "9.1.0", 25, "As above, for target 25."),
            new Floor("org.springframework.boot", "spring-boot", "3.5.16", 21,
                    "Boot 2.7 is end-of-life and cannot run Java 21. This corpus scores 3.5.x at "
                            + "one CRITICAL+HIGH against 2.7.18's tens, and 4.0.x back at two."));

    private Floors() {
    }

    /**
     * The version this artifact needs at this target, or empty if nothing here has an opinion.
     *
     * <p>The highest applicable row wins, so adding a JDK 26 entry later needs no edit anywhere else.
     */
    static String version(String artifact, int target) {
        return ALL.stream()
                .filter(f -> f.artifact().equals(artifact) && target >= f.sinceTarget())
                .max(Comparator.comparingInt(Floor::sinceTarget))
                .map(Floor::version)
                .orElse("");
    }

    /** Every rule, for a reader: grouped by artifact, oldest target first. */
    static List<Floor> all() {
        return ALL;
    }

    /**
     * The floors as a line each, for an agent that is told to apply them.
     *
     * <p>THE PROMPTS USED TO CARRY THESE NUMBERS TYPED OUT. Raising a floor in the table while the
     * preparer's instructions still named the old one would tell an agent to do one thing while the
     * code did another, and nothing would have flagged the disagreement.
     */
    static String forPrompt() {
        StringBuilder out = new StringBuilder();
        ALL.stream().map(Floor::coordinates).distinct().forEach(coordinates -> {
            List<Floor> rows = ALL.stream().filter(f -> f.coordinates().equals(coordinates))
                    .sorted(Comparator.comparingInt(Floor::sinceTarget)).toList();
            out.append("                - ").append(coordinates).append(": ");
            for (int i = 0; i < rows.size(); i++) {
                Floor f = rows.get(i);
                out.append(i > 0 ? ", " : "").append(f.version());
                if (f.sinceTarget() > 0) {
                    out.append(" from target ").append(f.sinceTarget());
                }
            }
            out.append('\n');
        });
        return out.toString();
    }

    /** What actually applies at one target, which is the useful view when reading a trace. */
    static List<Floor> at(int target) {
        return ALL.stream()
                .filter(f -> target >= f.sinceTarget())
                .filter(f -> f.version().equals(version(f.artifact(), target)))
                .toList();
    }
}
