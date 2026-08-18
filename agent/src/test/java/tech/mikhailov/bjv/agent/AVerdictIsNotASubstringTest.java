package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A VERDICT IS A WORD, NOT THE FIRST PLACE ITS LETTERS APPEAR.
 *
 * <p>{@link Bump#word} was {@code indexOf} over the whole lowercased reply, taking the earliest hit,
 * and three collisions follow from that in ordinary English: {@code done} sits inside "not done",
 * {@code again} inside "against", {@code sound} inside "unsound". The first is the expensive one,
 * because {@code done} was also the default when nothing matched: the approving answer was the
 * easiest verdict to trigger by accident, which is backwards for a construction whose only purpose
 * is that a reviewer can stop the work.
 */
class AVerdictIsNotASubstringTest {

    @Test
    void aDenialOfCompletionIsNotCompletion() {
        // The reply that would have closed a module with its target still at 11.
        String reply = "Not done — module `core` still declares maven.compiler.release 11.\n"
                + "again: raise the property in core/pom.xml";

        assertEquals("again", Bump.word(reply, "done", "again", "replan"));
    }

    @Test
    void everyOrdinaryPhrasingOfRefusalSurvives() {
        for (String opening : List.of("not done", "nothing done", "this is not done",
                "isn't done yet", "cannot be done", "never done")) {
            String reply = opening + ".\nagain: the pin is still below the floor";
            assertEquals("again", Bump.word(reply, "done", "again", "replan"),
                    "opening with \"" + opening + "\" is not agreement");
        }
    }

    @Test
    void againstIsNotAgain() {
        String reply = "replan: the plan works against a property the build never reads";

        assertEquals("replan", Bump.word(reply, "done", "again", "replan"));
    }

    @Test
    void unsoundIsNotSound() {
        // The security critic's rejection used to read as its approval.
        assertEquals("wrong-call",
                Bump.word("wrong-call: the delta is unsound", "sound", "wrong-call"));
        assertEquals("overclaimed", Bump.word("The reading is unsound.\noverclaimed: it credits the"
                + " bump with CVEs that were never reachable", "sound", "overclaimed"));
    }

    @Test
    void aLeadingVerdictLineWinsOverAnythingBelowIt() {
        // Every prompt asks for the word first, and when it arrives that way nothing else matters.
        String reply = "replan: this module inherits everything; nothing here is done or again";

        assertEquals("replan", Bump.word(reply, "done", "again", "replan"));
    }

    @Test
    void theVerdictSurvivesTheDecorationModelsAddToIt() {
        for (String decorated : List.of("**done**", "- done", "> done", "`done`", "  done  ",
                "done.", "done!", "done: every pin met")) {
            assertEquals("done", Bump.word(decorated, "done", "again", "replan"),
                    "decorated as: " + decorated);
        }
    }

    @Test
    void aPlainAgreementIsStillAgreement() {
        assertEquals("done", Bump.word("done: every pin is at or above its floor",
                "done", "again", "replan"));
        assertEquals("sound", Bump.word("sound", "sound", "wrong-call"));
    }

    @Test
    void silenceFallsBackRatherThanGuessing() {
        assertEquals("done", Bump.word("", "done", "again", "replan"));
        assertEquals("done", Bump.word(null, "done", "again", "replan"));
    }

    @Test
    void skippingAChildDoesNotSkipItsParent(@TempDir Path ws) throws IOException {
        // The asymmetry that makes this worse than an ordinary parsing bug: a module wrongly kept
        // costs a wasted diff, and a module wrongly skipped keeps its old target, which the gate
        // reads as the repository minimum and fails the whole bump.
        Files.writeString(ws.resolve("pom.xml"), """
                <project><artifactId>root</artifactId>
                  <modules><module>server</module></modules></project>
                """);
        Files.createDirectories(ws.resolve("server/protobuf"));
        Files.writeString(ws.resolve("server/pom.xml"), """
                <project><artifactId>server</artifactId>
                  <modules><module>protobuf</module></modules></project>
                """);
        Files.writeString(ws.resolve("server/protobuf/pom.xml"),
                "<project><artifactId>protobuf</artifactId></project>");
        List<Modules.Module> all = Modules.of(ws);
        String reply = "SKIP server/protobuf: written by the protobuf-maven-plugin";

        List<String> kept = all.stream()
                .filter(m -> m.isRoot() || !Bump.skipsForTest(reply, m))
                .map(m -> m.path()).toList();

        assertTrue(kept.contains("server"),
                "the aggregator that holds the subtree's compiler settings is kept: " + kept);
        assertFalse(kept.contains("server/protobuf"), "and the generated module is not");
    }

    @Test
    void aPrefixSiblingIsNotSkippedEither(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("pom.xml"), """
                <project><artifactId>root</artifactId>
                  <modules><module>app</module><module>app-generated</module></modules></project>
                """);
        Files.createDirectories(ws.resolve("app"));
        Files.createDirectories(ws.resolve("app-generated"));
        Files.writeString(ws.resolve("app/pom.xml"), "<project><artifactId>app</artifactId></project>");
        Files.writeString(ws.resolve("app-generated/pom.xml"),
                "<project><artifactId>g</artifactId></project>");
        String reply = "SKIP app-generated: emitted by the codegen plugin";

        List<Modules.Module> all = Modules.of(ws);
        assertFalse(Bump.skipsForTest(reply, all.stream()
                .filter(m -> m.path().equals("app")).findFirst().orElseThrow()),
                "app is not app-generated");
        assertTrue(Bump.skipsForTest(reply, all.stream()
                .filter(m -> m.path().equals("app-generated")).findFirst().orElseThrow()));
    }

    @Test
    void aModuleNamedOnlyInTheEvidenceIsNotSkipped(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("pom.xml"), """
                <project><artifactId>root</artifactId>
                  <modules><module>core</module><module>vendor</module></modules></project>
                """);
        Files.createDirectories(ws.resolve("core"));
        Files.createDirectories(ws.resolve("vendor"));
        Files.writeString(ws.resolve("core/pom.xml"), "<project><artifactId>c</artifactId></project>");
        Files.writeString(ws.resolve("vendor/pom.xml"), "<project><artifactId>v</artifactId></project>");
        // The prose after the colon names another module, and used to drop it.
        String reply = "SKIP vendor: a checked-in copy of a library that core depends on";

        List<Modules.Module> all = Modules.of(ws);
        assertFalse(Bump.skipsForTest(reply, all.stream()
                .filter(m -> m.path().equals("core")).findFirst().orElseThrow()),
                "the evidence half of the line is not searched");
    }
}
