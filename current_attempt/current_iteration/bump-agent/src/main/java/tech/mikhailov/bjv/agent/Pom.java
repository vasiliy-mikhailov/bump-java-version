package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level pom surgery, deliberately not a parser.
 *
 * <p>ElementTree-style re-serialisation strips comments and licence headers and reorders attributes,
 * and these files go through a diff-based gate where every spurious change is noise a reviewer has
 * to argue with. Anchored string edits keep the diff exactly as large as the change.
 *
 * <p>EVERY ENTRY POINT TOLERATES A PROJECT WITH NO ROOT POM, because roughly two in five of the
 * corpus are Gradle and a Maven-shaped edit is simply not applicable there. Each returns whether it
 * did anything, so a caller can record "not applicable" rather than believe it applied a floor it
 * did not. Throwing instead killed the whole bump before its baseline was even scored.
 */
final class Pom {

    private Pom() {
    }

    /** True when this project has a root pom at all; everything below is a no-op without one. */
    static boolean isMaven(Path ws) {
        return Files.isRegularFile(ws.resolve("pom.xml"));
    }

    /** A dependencyManagement entry in the ROOT pom: beats imported BOMs, covers transitive deps. */
    static boolean manage(Path ws, String g, String a, String v) throws IOException {
        if (!isMaven(ws)) {
            return false;
        }
        Path pom = ws.resolve("pom.xml");
        String s = Files.readString(pom);
        String entry = "<dependency><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version></dependency>";
        if (s.contains("<dependencyManagement>")) {
            s = s.replaceFirst("(<dependencyManagement>\\s*<dependencies>)",
                    "$1\n            " + Matcher.quoteReplacement(entry));
        } else {
            s = s.replaceFirst("(</project>)",
                    Matcher.quoteReplacement("  <dependencyManagement><dependencies>\n            "
                            + entry + "\n  </dependencies></dependencyManagement>\n") + "$1");
        }
        Files.writeString(pom, s);
        return true;
    }

    /** A direct dependency in the ROOT pom, inherited by every module. */
    static boolean addDependency(Path ws, String g, String a, String v, String scope)
            throws IOException {
        if (!isMaven(ws)) {
            return false;
        }
        Path pom = ws.resolve("pom.xml");
        String s = Files.readString(pom);
        String entry = "<dependency><groupId>" + g + "</groupId><artifactId>" + a
                + "</artifactId><version>" + v + "</version>"
                + (scope == null ? "" : "<scope>" + scope + "</scope>") + "</dependency>";
        // The root's own <dependencies>, not dependencyManagement's: match one that directly
        // follows the project or profile level by excluding the managed block's opening tag.
        Matcher m = Pattern.compile("(?<!<dependencyManagement>\\n)(  <dependencies>)").matcher(s);
        if (m.find()) {
            s = m.replaceFirst("$1\n    " + Matcher.quoteReplacement(entry));
        } else {
            s = s.replaceFirst("(</project>)",
                    Matcher.quoteReplacement("  <dependencies>\n    " + entry + "\n  </dependencies>\n")
                            + "$1");
        }
        Files.writeString(pom, s);
        return true;
    }

    /** Append tokens to the argLine property, creating it when absent. Preserves @{argLine} hooks. */
    static boolean extendArgLine(Path ws, List<String> tokens) throws IOException {
        if (!isMaven(ws)) {
            return false;
        }
        Path pom = ws.resolve("pom.xml");
        String s = Files.readString(pom);
        Matcher m = Pattern.compile("<argLine>([^<]*)</argLine>").matcher(s);
        if (m.find()) {
            String cur = m.group(1);
            StringBuilder add = new StringBuilder();
            for (String t : tokens) {
                if (!cur.contains(t)) {
                    add.append(' ').append(t);
                }
            }
            if (add.length() > 0) {
                s = s.substring(0, m.start(1)) + cur + add + s.substring(m.end(1));
            }
        } else {
            String line = String.join(" ", tokens);
            if (s.contains("<properties>")) {
                s = s.replaceFirst("(<properties>)",
                        "$1\n        <argLine>" + Matcher.quoteReplacement(line) + "</argLine>");
            } else {
                s = s.replaceFirst("(</project>)",
                        Matcher.quoteReplacement("  <properties><argLine>" + line
                                + "</argLine></properties>\n") + "$1");
            }
        }
        Files.writeString(pom, s);
        return true;
    }

    /** Pin a plugin's version wherever it is declared with one. */
    static void pluginVersion(Path ws, String artifactId, String v) throws IOException {
        for (Path pom : Walls.poms(ws)) {
            String s = Files.readString(pom);
            String out = s.replaceAll(
                    "(<artifactId>" + Pattern.quote(artifactId) + "</artifactId>\\s*<version>)[^<]+(</version>)",
                    "$1" + Matcher.quoteReplacement(v) + "$2");
            if (!out.equals(s)) {
                Files.writeString(pom, out);
            }
        }
    }

    /**
     * Pin the version of a {@code <parent>} declaration.
     *
     * <p>THIS IS THE ONLY THING A PARENT-DECLARED PROJECT READS. Every Maven Spring project in this
     * corpus declares its Boot line through {@code spring-boot-starter-parent} and none of them
     * reference {@code ${spring-boot.version}}, so the property pin that was supposed to guarantee
     * the measured-best patch wrote a property nothing consumed and left the version to whatever
     * the recipe happened to target.
     *
     * <p>Scoped to the matching parent block rather than the file, because a pom's own
     * {@code <version>} sits a few lines below its parent's and a looser pattern rewrites the
     * module's own coordinates.
     */
    static boolean parentVersion(Path ws, String artifactId, String v) throws IOException {
        Pattern block = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);
        Pattern names = Pattern.compile("<artifactId>\\s*" + Pattern.quote(artifactId)
                + "\\s*</artifactId>");
        boolean any = false;
        for (Path pom : Walls.poms(ws)) {
            String s = Files.readString(pom);
            Matcher m = block.matcher(s);
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String parent = m.group();
                if (names.matcher(parent).find()) {
                    String pinned = parent.replaceFirst("(<version>)[^<]+(</version>)",
                            "$1" + Matcher.quoteReplacement(v) + "$2");
                    if (!pinned.equals(parent)) {
                        parent = pinned;
                        changed = true;
                    }
                }
                m.appendReplacement(out, Matcher.quoteReplacement(parent));
            }
            m.appendTail(out);
            if (changed) {
                Files.writeString(pom, out.toString());
                any = true;
            }
        }
        return any;
    }

    /** Set a property in every pom that declares it; child properties shadow the parent's. */
    static void setPropertyEverywhere(Path ws, String name, String value) throws IOException {
        String pat = "(<" + Pattern.quote(name) + ">)[^<]*(</" + Pattern.quote(name) + ">)";
        boolean anywhere = false;
        for (Path pom : Walls.poms(ws)) {
            String s = Files.readString(pom);
            String out = s.replaceAll(pat, "$1" + Matcher.quoteReplacement(value) + "$2");
            if (!out.equals(s)) {
                Files.writeString(pom, out);
                anywhere = true;
            }
        }
        if (!anywhere) {
            if (!isMaven(ws)) {
                return;
            }
            Path root = ws.resolve("pom.xml");
            String s = Files.readString(root);
            if (s.contains("<properties>")) {
                s = s.replaceFirst("(<properties>)", "$1\n        <" + name + ">" + value
                        + "</" + name + ">");
            } else {
                s = s.replaceFirst("(</project>)", Matcher.quoteReplacement("  <properties><" + name
                        + ">" + value + "</" + name + "></properties>\n") + "$1");
            }
            Files.writeString(root, s);
        }
    }
}
