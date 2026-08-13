package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DID THE PIN ACTUALLY LAND — asked of the build files, not of whoever claimed to have applied it.
 *
 * <p>This is what makes a phase's loop terminate honestly. A producer that says it raised Lombok
 * and a critic that agrees are two opinions; the pom is the fact. The corpus has both failure
 * modes on record: a preparer answering NOTHING-TO-DO while its own stage recorded edits, and a
 * troubleshooter reporting a fix it had reverted a turn earlier.
 *
 * <p>Deliberately a READ. It changes nothing, so it can be run before a phase to see what is
 * needed, after a phase to see what landed, and by a critic that is not allowed to edit.
 */
final class Pins {

    /** One pin, and what the project currently says about it. */
    record State(Floors.Floor want, String found, boolean satisfied) {

        String describe() {
            if (satisfied) {
                return want.coordinates() + " " + (found.isBlank() ? "not present" : found) + " ok";
            }
            return want.coordinates() + " wants " + want.version()
                    + (found.isBlank() ? " but is not declared anywhere" : " but reads " + found);
        }
    }

    private Pins() {
    }

    /**
     * Every pin in a phase, with whether the workspace satisfies it.
     *
     * <p>A dependency the project does not use at all counts as satisfied: these are floors, not
     * requirements, and adding Lombok to a project that never had it would be a different bump.
     */
    static List<State> check(Path ws, List<Floors.Floor> wanted) throws IOException {
        String all = buildFiles(ws);
        List<State> out = new ArrayList<>();
        for (Floors.Floor f : wanted) {
            String found = declared(all, f);
            boolean present = !found.isBlank();
            boolean high = present && Migrate.compare(found, f.version()) >= 0;
            out.add(new State(f, found, !present || high));
        }
        return out;
    }

    /** The ones that are not satisfied, which is what a loop needs to decide whether to go again. */
    static List<State> outstanding(Path ws, List<Floors.Floor> wanted) throws IOException {
        return check(ws, wanted).stream().filter(s -> !s.satisfied()).toList();
    }

    /**
     * The version the build files currently give an artifact, or empty if it names it nowhere.
     *
     * <p>Deliberately loose about WHERE. A version can sit in a dependency, in
     * dependencyManagement, in a property the dependency then references, in a Gradle string
     * notation or in a wrapper properties file, and this is a check rather than an edit: finding
     * the number anywhere near the artifact is enough to say the floor is met. The recipes decide
     * placement; this only decides whether to run them again.
     */
    private static String declared(String all, Floors.Floor f) {
        if (f.artifact().equals("gradle-wrapper")) {
            Matcher m = Pattern.compile("gradle-([0-9]+(?:\\.[0-9]+)*)-(?:bin|all)\\.zip").matcher(all);
            return m.find() ? m.group(1) : "";
        }
        // group:artifact:version, the Gradle string form.
        Matcher gradle = Pattern.compile(Pattern.quote(f.group() + ":" + f.artifact())
                + ":([0-9][0-9A-Za-z.\\-]*)").matcher(all);
        if (gradle.find()) {
            return gradle.group(1);
        }
        // <artifactId>x</artifactId> ... <version>y</version>, the Maven form, within one element.
        Matcher maven = Pattern.compile("<artifactId>\\s*" + Pattern.quote(f.artifact())
                + "\\s*</artifactId>\\s*(?:<[^>]+>[^<]*</[^>]+>\\s*)*?<version>\\s*([^<$][^<]*)"
                + "</version>", Pattern.DOTALL).matcher(all);
        if (maven.find()) {
            return maven.group(1).strip();
        }
        // A property the dependency points at: <lombok.version>1.18.30</lombok.version>.
        Matcher property = Pattern.compile("<" + Pattern.quote(f.artifact())
                + "[.-]version>\\s*([^<]+)</").matcher(all);
        return property.find() ? property.group(1).strip() : "";
    }

    /** Every build file, concatenated, because a pin may live in any of them. */
    private static String buildFiles(Path ws) throws IOException {
        StringBuilder all = new StringBuilder();
        for (Path p : Walls.poms(ws)) {
            all.append(Files.readString(p)).append('\n');
        }
        try (var walk = Files.walk(ws, 4)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (name.equals("build.gradle") || name.equals("build.gradle.kts")
                        || name.equals("gradle.properties")
                        || name.equals("gradle-wrapper.properties")
                        || name.equals("libs.versions.toml")) {
                    all.append(Files.readString(p)).append('\n');
                }
            }
        }
        return all.toString();
    }
}
