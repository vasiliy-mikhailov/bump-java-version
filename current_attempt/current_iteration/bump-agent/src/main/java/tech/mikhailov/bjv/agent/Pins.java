package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DID THE PIN ACTUALLY LAND, AND WHERE, asked of the build files rather than of whoever claimed to
 * have applied it.
 *
 * <p>This is what makes a phase's loop terminate honestly. A producer that says it raised Lombok and
 * a critic that agrees are two opinions; the pom is the fact. The corpus has both failure modes on
 * record: a preparer answering NOTHING-TO-DO while its own stage recorded edits, and a
 * troubleshooter reporting a fix it had reverted a turn earlier.
 *
 * <p>THE ANSWER IS PER MODULE, and it used not to be. This concatenated every build file in the tree
 * into one string and returned the first version it matched anywhere, which answers about whichever
 * module the filesystem walked first. Sixteen of the twenty-seven multi-module repositories in this
 * corpus carry some package at more than one version, byte-buddy at three in one of them, so a
 * module declaring the floor could mask six that did not. The phase then closed reporting every pin
 * met and failed four stages later at the gate, where the evidence no longer says why.
 *
 * <p>Two rules follow from that. A module is judged on what its own build files declare, so
 * inheritance is the parent's business and the parent is a module too. And where a module names an
 * artifact more than once, the LOWEST version is the answer, because the floor is met only when
 * nothing sits below it.
 *
 * <p>Deliberately a READ. It changes nothing, so it can be run before a phase to see what is needed,
 * after a phase to see what landed, and by a verifier that is not allowed to edit.
 */
final class Pins {

    private Pins() {
    }

    private static void collect(List<String> out, Pattern p, String text) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            String v = m.group(1).strip();
            // AN UNRESOLVED PLACEHOLDER IS NOT A VERSION. `${lombok.version}` sorts below every
            // real number, and lowest-wins would make it the module's answer, so a project that
            // routes its versions through properties would read as permanently below every floor
            // and be sent round the loop until the budget ran out.
            if (!v.isBlank() && !v.contains("${") && !v.startsWith("$")) {
                out.add(v);
            }
        }
    }

    /**
     * The same text with comments removed.
     *
     * <p>A commented-out property read as a declaration, and a comment sitting between an
     * artifactId and its version let the Maven pattern skip across into a neighbouring dependency's
     * version. Both are ordinary in real poms, where the previous value is usually left in place
     * above the new one.
     */
    private static String uncommented(String text) {
        return text.replaceAll("(?s)<!--.*?-->", " ");
    }

    /**
     * The declared source/target pins in one module that are still BELOW the target JDK.
     *
     * <p>The other half of "did it land": a version floor asks whether a dependency is high enough,
     * and this asks whether the module has actually been told to compile for the new JDK. Both are
     * read from the files rather than from a diff, because a diff shows what someone changed and not
     * what remains.
     *
     * <p>Every dialect it can be written in: Maven properties and plugin configuration, Gradle's
     * sourceCompatibility and jvmTarget, and the toolchain's JavaLanguageVersion.of.
     */
    /**
     * Every dialect a Java level can be declared in.
     *
     * <p>The Gradle half of this was three spellings and the corpus uses at least eight. Missing one
     * used to mean the bumper was handed a shorter list; it now also means the phase SKIPS the
     * module, because a module with nothing below the target is left alone to keep a
     * seventeen-module project from spending fifty agent calls on modules that inherit everything.
     * A blind spot and a skip together are a module that is silently never bumped, so the pattern
     * and {@link #mentionsTarget} have to agree about what counts as a declaration.
     */
    private static final Pattern PIN = Pattern.compile(
            "<(?:maven\\.compiler\\.(?:source|target|release)|java\\.version|jdk\\.version"
                    + "|source|target|release|jvmTarget)>\\s*(?:1\\.)?(\\d+)\\s*<"
                    + "|JavaLanguageVersion\\.of\\((\\d+)\\)"
                    + "|jvmToolchain\\s*\\(\\s*(\\d+)\\s*\\)"
                    + "|JavaVersion\\.VERSION_(?:1_)?(\\d+)"
                    + "|JavaVersion\\.toVersion\\(\\s*['\"]?(?:1\\.)?(\\d+)"
                    + "|JvmTarget\\.JVM_(?:1_)?(\\d+)"
                    + "|release\\s*\\.\\s*set\\s*\\(\\s*(\\d+)\\s*\\)"
                    + "|(?:sourceCompatibility|targetCompatibility|jvmTarget)\\s*[=:]?\\s*"
                    + "['\"]?(?:1\\.)?(\\d+)");

    /** The same keywords, without a number, for deciding whether a module declares a level at all. */
    private static final Pattern MENTIONS = Pattern.compile(
            "maven\\.compiler\\.(?:source|target|release)|<java\\.version>|<jdk\\.version>"
                    + "|sourceCompatibility|targetCompatibility|jvmTarget|jvmToolchain"
                    + "|JavaLanguageVersion|JavaVersion\\.|JvmTarget\\.|release\\s*\\.\\s*set");

    static List<String> belowTarget(Path ws, Modules.Module module, List<Modules.Module> all,
                                    int target) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path f : Modules.buildFilesOf(ws, module, all)) {
            List<String> lines = Files.readAllLines(f);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = PIN.matcher(lines.get(i));
                while (m.find()) {
                    String v = null;
                    for (int g = 1; g <= m.groupCount() && v == null; g++) {
                        v = m.group(g);
                    }
                    if (v != null && Integer.parseInt(v) < target) {
                        out.add(ws.relativize(f) + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether this module declares a Java level of its own, in any dialect, parsed or not.
     *
     * <p>This is the guard on the skip. "Nothing below the target" and "declares nothing" are
     * different states and only the second is safe to skip: the first can also mean a spelling the
     * pattern above does not know, and skipping that leaves the module on its old level for the gate
     * to find as the repository minimum.
     */
    static boolean mentionsTarget(Path ws, Modules.Module module, List<Modules.Module> all)
            throws IOException {
        for (Path f : Modules.buildFilesOf(ws, module, all)) {
            if (MENTIONS.matcher(Files.readString(f)).find()) {
                return true;
            }
        }
        return false;
    }

    /** Every module's pins still below the target, which is what the gate will measure. */
    static List<String> belowTarget(Path ws, int target) throws IOException {
        List<Modules.Module> all = Modules.of(ws);
        List<String> out = new ArrayList<>();
        for (Modules.Module m : all) {
            out.addAll(belowTarget(ws, m, all, target));
        }
        return out;
    }
}
