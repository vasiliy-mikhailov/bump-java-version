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
 */
final class Pom {

    private Pom() {
    }

    /** A dependencyManagement entry in the ROOT pom: beats imported BOMs, covers transitive deps. */
    static void manage(Path ws, String g, String a, String v) throws IOException {
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
    }

    /** A direct dependency in the ROOT pom, inherited by every module. */
    static void addDependency(Path ws, String g, String a, String v, String scope)
            throws IOException {
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
    }

    /** Append tokens to the argLine property, creating it when absent. Preserves @{argLine} hooks. */
    static void extendArgLine(Path ws, List<String> tokens) throws IOException {
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
