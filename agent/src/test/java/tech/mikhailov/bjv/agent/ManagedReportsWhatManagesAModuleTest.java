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
 * WHAT MANAGES A MODULE IS RARELY WRITTEN IN THAT MODULE'S BUILD FILE.
 *
 * <p>Of the 277 Boot-managed modules in this corpus, 41 name {@code spring-boot-starter-parent} as
 * their own parent and a string match would find them. The other 236 would not: 185 inherit an
 * in-repo parent pom that is itself Boot, 40 carry the Gradle plugin, and 11 import
 * {@code spring-boot-dependencies} at import scope. Two of the 54 import-scope declarations are
 * written {@code ${quarkus.platform.artifact-id}}, so no literal match reaches them at all.
 *
 * <p>Every one of those shapes is here, because a module read as unmanaged when Boot manages it gets
 * the prompt that pins artifacts directly, and pinning a managed artifact overrides the managed set
 * and breaks the build the pin was meant to fix.
 *
 * <p>What is NOT asserted anywhere here is a platform. This reports; the detector judges.
 */
class ManagedReportsWhatManagesAModuleTest {

    @Test
    void aModuleThatNamesTheBootParentItself(@TempDir Path ws) throws IOException {
        // The 41-case, and the only one a predicate ever got right.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.5</version>
                  </parent>
                  <artifactId>app</artifactId>
                </project>
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(0), all);

        assertTrue(report.contains("org.springframework.boot:spring-boot-starter-parent"), report);
        assertTrue(report.contains("3.2.5"), report);
        // WHERE THE CHAIN LEFT THE REPOSITORY is the fact that names the managing set. A parent
        // outside the repository is where management comes from and cannot be edited here.
        assertTrue(report.contains("not a module of this repository"), report);
    }

    @Test
    void aModuleWhoseBootIsFourDirectoriesUp(@TempDir Path ws) throws IOException {
        // The 185-case: the majority, and the one a naive predicate misses. The module's own pom
        // does not contain the word "spring" anywhere.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.7.18</version>
                  </parent>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-parent</artifactId>
                  <packaging>pom</packaging>
                  <modules><module>web</module></modules>
                </project>
                """);
        Files.createDirectories(ws.resolve("web"));
        Files.writeString(ws.resolve("web/pom.xml"), """
                <project>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>acme-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>web</artifactId>
                </project>
                """);
        List<Modules.Module> all = Modules.of(ws);
        assertFalse(Files.readString(ws.resolve("web/pom.xml")).contains("spring"),
                "the premise of this test: nothing in the module's own file says Boot");

        String report = Managed.report(ws, all.get(1), all);

        assertTrue(report.contains("com.acme:acme-parent"), report);
        assertTrue(report.contains("which is pom.xml in this repository"),
                "the step that has to be followed rather than reported as foreign: " + report);
        assertTrue(report.contains("org.springframework.boot:spring-boot-starter-parent"), report);
        assertTrue(report.contains("2.7.18"), report);
    }

    @Test
    void aModuleInsideABootRepoThatBootDoesNotManage(@TempDir Path ws) throws IOException {
        // 80 of the 357 modules inside Boot repositories are outside the managed set, so the
        // question is per module and answering it per repository is wrong one time in five.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>2.7.18</version>
                  </parent>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-parent</artifactId>
                  <modules><module>tooling</module></modules>
                </project>
                """);
        Files.createDirectories(ws.resolve("tooling"));
        Files.writeString(ws.resolve("tooling/pom.xml"), """
                <project>
                  <groupId>com.acme.tools</groupId>
                  <artifactId>tooling</artifactId>
                  <dependencies><dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                    <version>32.1.3-jre</version>
                  </dependency></dependencies>
                </project>
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(1), all);

        assertFalse(report.contains("spring"),
                "it aggregates under a Boot pom but inherits nothing from it: " + report);
        assertTrue(report.contains("declares no parent, so the chain ends here"), report);
    }

    @Test
    void aGradleModuleCarryingTheBootPlugin(@TempDir Path ws) throws IOException {
        // The 40-case. A plugin id with no version is still the plugin: the version is routinely
        // fixed by a settings pluginManagement block that this module's file never mentions.
        Files.writeString(ws.resolve("settings.gradle"), "rootProject.name = 'app'\n");
        Files.writeString(ws.resolve("build.gradle"), """
                plugins {
                    id 'org.springframework.boot' version '3.2.5'
                    id 'java'
                }
                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter-web'
                }
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(0), all);

        assertTrue(report.contains("org.springframework.boot"), report);
        assertTrue(report.contains("3.2.5"), report);
        assertTrue(report.contains("java") && report.contains("(no version here)"),
                "a version-less id is reported, not dropped: " + report);
    }

    @Test
    void aGradleSubprojectWhosePluginIsDeclaredInTheRoot(@TempDir Path ws) throws IOException {
        // Gradle applies from the root outwards, so a subproject that Boot manages can have a build
        // file that never says Boot. Reading only the module's own file is the Gradle spelling of
        // the same mistake the Maven parent chain punishes.
        Files.writeString(ws.resolve("settings.gradle"), """
                rootProject.name = 'app'
                include ':web'
                """);
        Files.writeString(ws.resolve("build.gradle"), """
                plugins {
                    id 'org.springframework.boot' version '3.2.5' apply false
                }
                """);
        Files.createDirectories(ws.resolve("web"));
        Files.writeString(ws.resolve("web/build.gradle"), """
                dependencies {
                    implementation platform('com.acme:acme-bom:1.4.0')
                }
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(1), all);

        assertTrue(report.contains("org.springframework.boot"), report);
        assertTrue(report.contains("which is above this module"),
                "the plugin is the root's, and saying so is what stops an edit landing there: "
                        + report);
        assertTrue(report.contains("platform('com.acme:acme-bom:1.4.0')"),
                "and its own imported platform is its own: " + report);
    }

    @Test
    void aGradleModuleCarryingBootTheOldWay(@TempDir Path ws) throws IOException {
        // Before the plugins block there was a buildscript classpath and a mavenBom import, and
        // half this corpus predates the plugins block.
        Files.writeString(ws.resolve("settings.gradle"), "rootProject.name = 'app'\n");
        Files.writeString(ws.resolve("build.gradle"), """
                buildscript {
                    repositories { mavenCentral() }
                    dependencies {
                        classpath 'org.springframework.boot:spring-boot-gradle-plugin:2.7.18'
                    }
                }
                apply plugin: 'org.springframework.boot'
                dependencyManagement {
                    imports {
                        mavenBom 'org.springframework.boot:spring-boot-dependencies:2.7.18'
                    }
                }
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(0), all);

        // The classpath line is inside a nested dependencies block, so the read has to balance
        // braces rather than stop at the first closing one.
        assertTrue(report.contains("spring-boot-gradle-plugin:2.7.18"), report);
        assertTrue(report.contains("mavenBom 'org.springframework.boot:spring-boot-dependencies:2.7.18'"),
                report);
    }

    @Test
    void anImportedBomWhoseArtifactIdIsAProperty(@TempDir Path ws) throws IOException {
        // Two of the 54 import-scope declarations in this corpus are written exactly like this, so
        // no literal string match will ever find them. Dropping the row because it cannot be
        // resolved reports a Quarkus module as managed by nothing, which is the opposite answer.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId>
                  <artifactId>app</artifactId>
                  <properties>
                    <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
                    <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
                    <quarkus.platform.version>3.8.1</quarkus.platform.version>
                  </properties>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>${quarkus.platform.group-id}</groupId>
                    <artifactId>${quarkus.platform.artifact-id}</artifactId>
                    <version>${quarkus.platform.version}</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(0), all);

        assertTrue(report.contains("${quarkus.platform.artifact-id}"),
                "the row is reported as the indirection it is: " + report);
        assertTrue(report.contains("quarkus-bom"),
                "and resolved out loud where the repository declares it: " + report);
        assertTrue(report.contains("3.8.1"), report);
        assertTrue(report.contains("type pom"), report);
    }

    @Test
    void anImportedBomDeclaredInAParentPom(@TempDir Path ws) throws IOException {
        // The 11-case. The import reaches this module from a pom it does not own, and raising a
        // version there is an edit to somebody else's module, so the row says where it came from.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId>
                  <artifactId>acme-parent</artifactId>
                  <modules><module>web</module></modules>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-dependencies</artifactId>
                    <version>3.2.5</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency></dependencies></dependencyManagement>
                </project>
                """);
        Files.createDirectories(ws.resolve("web"));
        Files.writeString(ws.resolve("web/pom.xml"), """
                <project>
                  <parent>
                    <groupId>com.acme</groupId>
                    <artifactId>acme-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>web</artifactId>
                </project>
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(1), all);

        assertTrue(report.contains("spring-boot-dependencies"), report);
        assertTrue(report.contains("which is above this module"),
                "whose pom the import lives in is part of the fact: " + report);
    }

    @Test
    void aModuleWithNoneOfThisSaysSoSectionBySection(@TempDir Path ws) throws IOException {
        // "Nothing manages this module" and "this was not looked at" are different answers, and a
        // reader acting on the second goes hunting for a file that does not exist. So the sections
        // are printed empty rather than omitted.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <groupId>com.acme</groupId>
                  <artifactId>plain</artifactId>
                  <dependencies><dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                    <version>32.1.3-jre</version>
                  </dependency></dependencies>
                </project>
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(0), all);

        assertTrue(report.contains("the <parent> this module declares"), report);
        assertTrue(report.contains("the parent chain, followed inside this repository"), report);
        assertTrue(report.contains("dependencyManagement entries at <scope>import</scope>"), report);
        assertTrue(report.contains("This pom declares no parent"), report);
        assertTrue(report.contains("(none, in this module or in any pom above it"), report);
        // A plain compile dependency is not a managing set, whatever version it carries.
        assertFalse(report.contains("guava"), report);
    }

    @Test
    void aCommentedOutImportIsNotAnImport(@TempDir Path ws) throws IOException {
        // The rule Declared already keeps, kept here too, because a BOM commented out across four
        // lines is how a project records the thing it stopped using.
        Files.writeString(ws.resolve("pom.xml"), """
                <project>
                  <artifactId>app</artifactId>
                  <dependencyManagement><dependencies>
                  <!--<dependency>
                    <groupId>io.quarkus.platform</groupId>
                    <artifactId>quarkus-bom</artifactId>
                    <version>3.8.1</version>
                    <type>pom</type>
                    <scope>import</scope>
                  </dependency>-->
                  </dependencies></dependencyManagement>
                </project>
                """);
        List<Modules.Module> all = Modules.of(ws);

        String report = Managed.report(ws, all.get(0), all);

        assertFalse(report.contains("quarkus-bom"), report);
        assertTrue(report.contains("(none, in this module or in any pom above it"), report);
    }

    @Test
    void theLabelledLineIsTheAnswerAndTheProseIsTheReason() {
        // A detector names every platform it considered, so the first mention of "spring-boot" in an
        // answer is routinely inside the sentence saying Boot is not what manages this module.
        assertEquals("spring-boot", Managed.platformIn(
                "PLATFORM: spring-boot\nThe chain reaches spring-boot-starter-parent 3.2.5."));
        assertEquals("quarkus", Managed.platformIn(
                "**PLATFORM:** quarkus\nThe import scope BOM is ${quarkus.platform.artifact-id}."));
        assertEquals("adhoc", Managed.platformIn(
                "There is a spring-boot starter here, but no parent and no BOM manages its version.\n"
                        + "PLATFORM: adhoc"));
        assertEquals("spring-boot", Managed.platformIn(
                "platform: spring-boot (the parent chain leaves the repository at Boot 2.7.18)"));
    }

    @Test
    void anAnswerOutsideTheClosedSetIsAdhocRatherThanADeadRun() {
        // The detector is one agent pair in a walk of thirty and its answer selects a prompt
        // directory. A run that dies because a model wrote "micronaut" loses a bump over a word,
        // and adhoc is the regime that asks for evidence before every pin.
        assertEquals("adhoc", Managed.platformIn("PLATFORM: micronaut\nIt uses the micronaut BOM."));
        assertEquals("adhoc", Managed.platformIn("Nothing here manages versions as far as I can see."));
        assertEquals("adhoc", Managed.platformIn(""));
        assertEquals("adhoc", Managed.platformIn(null));

        assertEquals(List.of("spring-boot", "quarkus", "adhoc"), Managed.PLATFORMS,
                "each word names a prompt directory, so a fourth would name one that is not there");
        for (String said : List.of("PLATFORM:", "PLATFORM: ???", "platform = maven",
                "PLATFORM: spring boot", "platform:adhoc, nothing manages it")) {
            assertTrue(Managed.PLATFORMS.contains(Managed.platformIn(said)), said);
        }
    }
}
