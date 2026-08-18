package tech.mikhailov.bjv.jvm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A BLANK VERSION IS A STATEMENT OF OWNERSHIP, SO THE ROW SAYS WHO OWNS IT.
 *
 * <p>The report used to print "(managed elsewhere)" for a dependency that carries no version, and
 * that string occurs in none of the thirty prompt files and in no tool description. The one signal
 * that decides whether an artifact may be raised at all reached every agent and said nothing to any
 * of them, so agents pinned managed artifacts, which overrides the managing set for that single
 * line and leaves the rest of the set where it was.
 *
 * <p>Prompts keyed by platform do not make this redundant. 22.4% of the modules inside the Spring
 * Boot repositories in this corpus are not Boot-managed, and a Boot pom carries managed and
 * self-versioned dependencies in the same block. The platform chooses which prompt an agent reads;
 * this row is what tells that agent which coordinates in front of it are not its to set.
 */
class ABlankVersionNamesItsOwnerTest {

    /** A Boot module with one row it may raise and one row it may not, which is the usual shape. */
    private static final String BOOT_PARENT_POM = """
            <project>
              <parent>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-parent</artifactId>
                <version>2.7.5</version>
              </parent>
              <dependencies>
                <dependency>
                  <groupId>org.springframework.boot</groupId>
                  <artifactId>spring-boot-starter-web</artifactId>
                </dependency>
                <dependency>
                  <groupId>org.projectlombok</groupId>
                  <artifactId>lombok</artifactId>
                  <version>1.18.20</version>
                </dependency>
              </dependencies>
            </project>
            """;

    @Test
    void aManagedRowNamesItsManagerAndASelfVersionedRowKeepsItsNumber(@TempDir Path ws)
            throws IOException {
        Files.writeString(ws.resolve("pom.xml"), BOOT_PARENT_POM);

        String report = Declared.report(ws, Modules.of(ws));

        assertTrue(row(report, "org.springframework.boot:spring-boot-starter-web").contains(
                        "(managed by org.springframework.boot:spring-boot-starter-parent 2.7.5)"),
                "the starter moves when Boot moves, and the row now says so: " + report);
        assertTrue(row(report, "org.projectlombok:lombok").contains("1.18.20"),
                "a version of its own is still its own: " + report);
        assertFalse(report.contains("(managed elsewhere)"),
                "the phrasing no prompt has ever mentioned: " + report);
    }

    @Test
    void anImportedSetIsNamedWhenThereIsNoParentToName(@TempDir Path ws) throws IOException {
        // Eleven modules in this corpus reach Boot by importing spring-boot-dependencies rather
        // than through a parent, and the artifact that must not be pinned is the same either way.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-dependencies</artifactId>
                      <version>2.7.5</version>
                      <type>pom</type>
                      <scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies><dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-web</artifactId>
                  </dependency></dependencies>
                </project>
                """);

        String managed = row(Declared.report(ws, Modules.of(ws)),
                "org.springframework.boot:spring-boot-starter-web");

        assertTrue(managed.contains(
                "(managed by org.springframework.boot:spring-boot-dependencies 2.7.5)"), managed);
    }

    @Test
    void theParentIsPreferredBecauseThereIsOnlyEverOneOfIt(@TempDir Path ws) throws IOException {
        // A module has exactly one parent and any number of imports, so the parent is the answer
        // that cannot be ambiguous.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>acme-parent</artifactId>
                    <version>9.4</version>
                  </parent>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>io.quarkus.platform</groupId>
                      <artifactId>quarkus-bom</artifactId>
                      <version>3.2.0</version>
                      <type>pom</type>
                      <scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies><dependency>
                    <groupId>io.quarkus</groupId><artifactId>quarkus-resteasy</artifactId>
                  </dependency></dependencies>
                </project>
                """);

        String managed = row(Declared.report(ws, Modules.of(ws)), "io.quarkus:quarkus-resteasy");

        assertTrue(managed.contains("(managed by com.acme:acme-parent 9.4)"), managed);
        assertFalse(managed.contains("quarkus-bom"), "one row, one answer: " + managed);
    }

    @Test
    void aModuleThatNamesNoManagerSaysSoRatherThanGuessing(@TempDir Path ws) throws IOException {
        // The honest answer for a module whose own files name nobody. "Something this module does
        // not name" is actionable, because it sends a reader to what resolved rather than to a
        // coordinate it invented, and it is countable afterwards. A guess is neither.
        Files.writeString(ws.resolve("pom.xml"), """
                <project><dependencies><dependency>
                  <groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                </dependency></dependencies></project>
                """);

        String report = Declared.report(ws, Modules.of(ws));

        assertTrue(row(report, "org.projectlombok:lombok")
                .contains("(managed by something this module does not name)"), report);
    }

    @Test
    void anImportWrittenAsAPropertyIsNamedTheWayItIsWritten(@TempDir Path ws) throws IOException {
        // Two of the fifty-four import-scope declarations in this corpus are spelled with
        // properties, so no literal match will ever find them. Printing what the file says keeps
        // the row, and the property it points at is a row in this same report.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <properties>
                    <quarkus.platform.version>3.2.0</quarkus.platform.version>
                  </properties>
                  <dependencyManagement><dependencies>
                    <dependency>
                      <groupId>${quarkus.platform.group-id}</groupId>
                      <artifactId>${quarkus.platform.artifact-id}</artifactId>
                      <version>${quarkus.platform.version}</version>
                      <type>pom</type><scope>import</scope>
                    </dependency>
                  </dependencies></dependencyManagement>
                  <dependencies><dependency>
                    <groupId>io.quarkus</groupId><artifactId>quarkus-resteasy</artifactId>
                  </dependency></dependencies>
                </project>
                """);

        String report = Declared.report(ws, Modules.of(ws));

        assertTrue(row(report, "io.quarkus:quarkus-resteasy")
                .contains("${quarkus.platform.artifact-id}"), report);
        assertTrue(row(report, "${quarkus.platform.version}").contains("3.2.0"),
                "and the number it resolves to is right there: " + report);
    }

    @Test
    void theColumnsStayStraightWhenAManagerIsNamed(@TempDir Path ws) throws IOException {
        // A manager's coordinates are several times as wide as a version number. Against a fixed
        // column the last field would step in and out as managed and self-versioned rows alternate,
        // and that alternation is every Boot pom rather than a corner case.
        Files.writeString(ws.resolve("pom.xml"), BOOT_PARENT_POM);

        String report = Declared.report(ws, Modules.of(ws));
        Set<Integer> lastColumn = new LinkedHashSet<>();
        for (String line : report.lines().filter(l -> l.startsWith("  ")).toList()) {
            String[] fields = line.trim().split("\\s+");
            lastColumn.add(line.length() - fields[fields.length - 1].length());
        }

        assertEquals(1, lastColumn.size(),
                "where a version was found starts at one column: " + lastColumn + "\n" + report);
    }

    /**
     * One report line, found by its coordinates column rather than anywhere on the line.
     *
     * <p>A managed row now carries its manager's coordinates in the version column, so a plain
     * containment check over the whole report passes for the wrong row. That is the same mistake as
     * matching "ok" inside "lombok", which an earlier test in this package made once already.
     */
    private static String row(String report, String coordinates) {
        return report.lines().filter(line -> line.startsWith("  " + coordinates + " "))
                .findFirst().orElseThrow(() ->
                        new AssertionError("no row for " + coordinates + " in:\n" + report));
    }
}
