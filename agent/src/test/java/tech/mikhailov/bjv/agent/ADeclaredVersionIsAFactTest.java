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
 * THE TOOL REPORTS; IT DOES NOT DECIDE.
 *
 * <p>Its predecessor took the floor list and answered "satisfied" per pin, and to do that it had to
 * be told which artifacts mattered — by a regex parsing the same prose the agents read. When the two
 * readers disagreed the regex won silently, and every Spring project in the corpus kept its original
 * Boot version while the log said "every pin met".
 *
 * <p>So the only thing asserted here is that the facts are complete and honest. Which of them is
 * outstanding is the planner's judgement, made against the floor list in its prompt.
 */
class ADeclaredVersionIsAFactTest {

    @Test
    void itSeesTheParentBlockWhereAMavenProjectSaysWhichBootItIsOn() {
        // The declaration the old check was blind to, which is why the Spring floor never fired.
        List<Declared.Version> found = Declared.in("""
                <project><parent>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-parent</artifactId>
                  <version>3.2.5</version>
                </parent></project>
                """, "pom.xml");

        Declared.Version boot = found.stream()
                .filter(v -> v.coordinates().endsWith("spring-boot-starter-parent"))
                .findFirst().orElseThrow(() -> new AssertionError("not seen: " + found));
        assertEquals("3.2.5", boot.value());
        assertEquals("parent", boot.where(), "which decides the recipe that can move it");
    }

    @Test
    void aStarterWithNoVersionSaysSoRatherThanBeingOmitted() {
        // "spring-boot-starter-web has no version" and "spring-boot-starter-web is absent" lead a
        // reader to opposite conclusions, and only one of them is true here.
        List<Declared.Version> found = Declared.in("""
                <project><dependencies><dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency></dependencies></project>
                """, "pom.xml");

        Declared.Version starter = found.stream()
                .filter(v -> v.coordinates().endsWith("spring-boot-starter-web"))
                .findFirst().orElseThrow(() -> new AssertionError("not seen: " + found));
        assertEquals("(managed elsewhere)", starter.value());
    }

    @Test
    void aPropertyIsReportedAsTheIndirectionItIs() {
        List<Declared.Version> found = Declared.in(
                "<project><properties><lombok.version>1.18.20</lombok.version></properties></project>",
                "pom.xml");

        Declared.Version p = found.stream().filter(v -> v.where().equals("property"))
                .findFirst().orElseThrow(() -> new AssertionError("not seen: " + found));
        assertEquals("${lombok.version}", p.coordinates());
        assertEquals("1.18.20", p.value());
    }

    @Test
    void gradleStringNotationAndPluginsAndTheWrapper() {
        List<Declared.Version> found = Declared.in("""
                plugins { id("org.springframework.boot") version "3.1.0" }
                dependencies { implementation 'org.projectlombok:lombok:1.18.20' }
                """, "build.gradle");

        assertTrue(found.stream().anyMatch(v -> v.coordinates().equals("org.projectlombok:lombok")
                && v.value().equals("1.18.20")), found.toString());
        assertTrue(found.stream().anyMatch(
                v -> v.coordinates().equals("org.springframework.boot")
                        && v.value().equals("3.1.0")), found.toString());

        List<Declared.Version> wrapper = Declared.in(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip",
                "gradle-wrapper.properties");
        assertTrue(wrapper.stream().anyMatch(v -> v.value().equals("8.5")), wrapper.toString());
    }

    @Test
    void aCommentedOutDependencyIsNotADeclaration() {
        List<Declared.Version> found = Declared.in(
                "<project><!--<dependency><artifactId>x</artifactId><version>1</version></dependency>-->"
                        + "</project>", "pom.xml");

        assertTrue(found.isEmpty(), "found: " + found);
    }

    @Test
    void everyModuleIsListedIncludingTheOnesThatDeclareNothing(@TempDir Path ws) throws IOException {
        // "inherits everything from its parent" and "was not looked at" are different answers, and a
        // reader acting on the second goes looking for a file that does not exist.
        Files.writeString(ws.resolve("pom.xml"), """
                <project><modules><module>common</module></modules>
                  <properties><lombok.version>1.18.20</lombok.version></properties></project>
                """);
        Files.createDirectories(ws.resolve("common"));
        Files.writeString(ws.resolve("common/pom.xml"), "<project><artifactId>common</artifactId></project>");

        String report = Declared.report(ws, Modules.of(ws));

        assertTrue(report.contains("module root"), report);
        assertTrue(report.contains("module common"), report);
        assertTrue(report.contains("declares no versions of its own"), report);
        assertTrue(report.contains("1.18.20"), report);
    }

    @Test
    void aModulesRowsStopWhereTheNextModuleBegins(@TempDir Path ws) throws IOException {
        // The bug this whole design replaces: one module's declaration answering for all of them.
        Files.writeString(ws.resolve("pom.xml"), """
                <project><modules><module>common</module></modules>
                  <properties><lombok.version>1.18.30</lombok.version></properties></project>
                """);
        Files.createDirectories(ws.resolve("common"));
        Files.writeString(ws.resolve("common/pom.xml"), """
                <project><artifactId>common</artifactId>
                  <properties><lombok.version>1.18.20</lombok.version></properties></project>
                """);

        String report = Declared.report(ws, Modules.of(ws));
        String common = report.substring(report.indexOf("module common"));

        assertTrue(common.contains("1.18.20"), "its own: " + common);
        assertFalse(common.contains("1.18.30"), "not the root's: " + common);
    }

    @Test
    void nothingHereJudgesAnything() {
        // STRUCTURAL, not a substring hunt: the first version of this test looked for "ok" in the
        // record's toString and matched the "ok" inside "lombok", which is the same class of
        // mistake as the regex this whole file replaces.
        //
        // The moment this tool carries a verdict it needs a floor list, and the floor list is prose
        // in a prompt that only a model should be reading. A Version has four fields and every one
        // of them is an observation.
        assertEquals(4, Declared.Version.class.getRecordComponents().length,
                "a fifth field would be somewhere for an opinion to live");
        for (var component : Declared.Version.class.getRecordComponents()) {
            assertEquals(String.class, component.getType(),
                    component.getName() + " is not a plain reading");
        }

        // And `where` says where it was found, never whether it is good enough.
        List<Declared.Version> found = Declared.in(
                "<project><properties><lombok.version>1.0</lombok.version></properties></project>",
                "pom.xml");
        assertEquals(List.of("property"), found.stream().map(Declared.Version::where).toList());
    }
}
