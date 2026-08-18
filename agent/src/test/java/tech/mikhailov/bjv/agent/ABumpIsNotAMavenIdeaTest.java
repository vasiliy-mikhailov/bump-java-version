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
 * A VERSION FLOOR IS A FACT ABOUT AN ARTIFACT. THE BUILD TOOL IS NOT PART OF IT.
 *
 * <p>Nothing in {@link Floors} says maven or gradle, and nothing should: there is no world in which
 * one build tool can reach Spring Boot 2.7.18 and the other cannot. For four hundred bumps the
 * harness behaved as though there were. {@code apply_recipe} was one command,
 * {@code mvn rewrite-maven-plugin:run}, and half this corpus by repository is Gradle-only.
 *
 * <p>MEASURED ACROSS 405 TRACES, THEN PUT THROUGH A SKEPTIC, WHICH TOOK HALF OF IT APART. What
 * survived: of 622 Gradle apply_recipe calls, zero ever returned rc=0, and all 491 that reached
 * Maven at all carry one line, "Goal requires a project to execute but there is no POM". 582 were
 * issued after that bump had already seen the error once. 89 of 178 distinct repositories are
 * Gradle-only. The pin phases hold no editor, so on a pom-less root the phase could read the
 * violated floor in full and had no way to act: pin doers made zero edit_file calls in the corpus,
 * while the phases that hold one made 1041 on those same repositories.
 *
 * <p>WHAT DID NOT SURVIVE, recorded because it is the more useful half. The raw PASS gap of 88.1
 * per cent against 58.6 is mostly an artefact of no-baseline, which is decided before any pin agent
 * exists and has its own cause: the Maven baseline carries -Dmaven.test.failure.ignore=true and the
 * Gradle one does not. Among bumps that reached a gate it is 90.9 against 89.5. The CVE gap is
 * wider in the sweep with zero apply_recipe calls than in the sweeps with the tool, and where the
 * tool exists per-bump movement is 38.0 against 38.5. The repository that spent 132 calls and
 * passed at 225 findings to 225 does the same at one call and at none.
 *
 * <p>So this fixes a mechanism that was measurably, totally broken, and the outcome it buys is not
 * yet measured. Saying otherwise would make this comment the folklore the rest of them warn about.
 * The Maven actuator is inert on its own terms too: 143 of 229 instrumented runs changed nothing,
 * so the honest contrast is 37.6 per cent effective against 0, not 955 against 0.
 *
 * <p>The agents diagnosed it themselves, repeatedly, and were right: "The tool appears to always
 * invoke the Maven rewrite plugin regardless of the recipe type, making it impossible to use
 * apply_recipe for Gradle projects."
 */
class ABumpIsNotAMavenIdeaTest {

    @Test
    void theActuatorIsChosenFromTheProjectRatherThanFromTheAgent(@TempDir Path ws)
            throws IOException {
        assertEquals("", Migrate.actuatorFor(ws), "an empty directory has nothing to run against");

        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        assertEquals("maven", Migrate.actuatorFor(ws));

        Files.writeString(ws.resolve("build.gradle"), "plugins { id 'java' }");
        assertEquals("both", Migrate.actuatorFor(ws), "a repository can be both, and one bump");

        Files.delete(ws.resolve("pom.xml"));
        assertEquals("gradle", Migrate.actuatorFor(ws));
    }

    @Test
    void aSettingsFileAloneIsStillAGradleProject(@TempDir Path ws) throws IOException {
        // A root that only aggregates has no build.gradle of its own. It is still where the one
        // rewriteRun invocation has to start, because that invocation reaches every subproject.
        Files.writeString(ws.resolve("settings.gradle.kts"), "include(\"lib\")");
        assertEquals("gradle", Migrate.actuatorFor(ws));
    }

    @Test
    void theInitScriptCarriesTheThingsThatWereEarnedByAFailure(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("rewrite.yml"), "name: com.bjv.Pins\nrecipeList: []\n");
        String init = new Migrate(ws, "", null).initScript();

        // The plugin is pinned by ENGINE, not by date: 7.33.0 carries rewrite-bom 8.83.0, which is
        // what rewrite-maven-plugin 6.40.0 carries, so a Gradle verdict and a Maven verdict are
        // measurements of one program. Taking the newest would quietly make them two.
        assertTrue(init.contains("org.openrewrite:plugin:7.33.0"), init);
        assertFalse(init.contains("latest.release"),
                "a dynamic selector needs a metadata fetch and makes a run unreproducible");

        // An init script's own classpath resolves before pluginManagement, buildscript or project
        // repositories exist, so the host's nexus-mirror.init.gradle does not reach it. Without
        // this the plugin comes from the open internet or not at all.
        assertTrue(init.contains("initscript"), init);
        // WRITTEN IN, NOT READ OUT. jvm-run passes three variables into the container and this is
        // not one of them, so a getenv here resolves to nothing and silently takes a fallback that
        // happens to be right. A configured mirror would have been ignored without a word.
        assertTrue(init.contains("http://nexus:8081/repository/maven-public/"), init);
        assertFalse(init.contains("System.getenv"), init);
        // The mirror is http and Gradle 6+ refuses plaintext without the opt-in; on 5.x the
        // property does not exist, which is why it is inside a catch.
        assertTrue(init.contains("allowInsecureProtocol"), init);
        assertTrue(init.contains("catch (Throwable ignored)"), init);

        // One document drives both actuators, so the recipe file is shared rather than duplicated.
        assertTrue(init.contains("setConfigFile"), init);
        assertTrue(init.contains("com.bjv.Pins"), "the active recipe is the one on disk: " + init);

        // Strictly better than the Maven side, which reports a name it could not resolve as a
        // successful run that changed nothing.
        assertTrue(init.contains("setFailOnInvalidActiveRecipes(true)"), init);

        // FAIL_ON_PROJECT_REPOS rejects the repository injection. Measured: the run still works
        // off the settings-level repositories, so it cannot be fatal.
        assertTrue(init.contains("project repositories refused"), init);
    }

    @Test
    void theGeneratedDocumentCoversEveryPlaceOneVersionCanBeWritten() {
        String yaml = Rewrites.bumpYaml("org.springframework.boot", "spring-boot-gradle-plugin",
                "2.7.18");

        assertTrue(yaml.startsWith("type: specs.openrewrite.org/v1beta/recipe"),
                "the document kind has exactly one legal value: " + yaml);
        assertTrue(yaml.contains("recipeList:"), yaml);

        // Four arms because one artifact has four homes and the project decides which. Each name
        // was read out of the jar the actuators load, not remembered.
        assertTrue(yaml.contains("org.openrewrite.maven.UpgradeDependencyVersion:"), yaml);
        assertTrue(yaml.contains("org.openrewrite.maven.UpgradeParentVersion:"), yaml);
        assertTrue(yaml.contains("org.openrewrite.gradle.UpgradeDependencyVersion:"), yaml);
        assertTrue(yaml.contains("org.openrewrite.gradle.plugins.UpgradePluginVersion:"),
                "the plugins block is how a Gradle project usually says which Boot it is on, and it "
                        + "is the arm that fired on the smoke project: " + yaml);

        // overrideManagedVersion belongs to the Maven arm alone; the Gradle recipe has no such
        // parameter and an unknown one fails validation where validation is strict.
        assertEquals(1, yaml.split("overrideManagedVersion", -1).length - 1, yaml);

        // The plugins arm keys on the id, which is the group, and takes no artifactId at all.
        assertTrue(yaml.contains("pluginIdPattern: org.springframework.boot"), yaml);

        for (String line : yaml.lines().toList()) {
            assertFalse(line.stripTrailing().endsWith(":") && line.startsWith("      "),
                    "an argument with no value: " + line);
        }
    }

    @Test
    void theMigrationIsDerivedFromTheTargetRatherThanRemembered() {
        assertEquals("org.openrewrite.java.spring.boot2.UpgradeSpringBoot_2_7",
                Rewrites.migrationFor("org.springframework.boot", "2.7.18"));
        assertEquals("org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5",
                Rewrites.migrationFor("org.springframework.boot", "3.5.16"));
        assertEquals("org.openrewrite.java.spring.boot4.UpgradeSpringBoot_4_0",
                Rewrites.migrationFor("org.springframework.boot", "4.0.0"));
        // The patch is the recipe's business, so naming the line alone means the same thing.
        assertEquals("org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_5",
                Rewrites.migrationFor("org.springframework.boot", "3.5"));
    }

    @Test
    void aRecipeThatWasNeverPublishedIsRefusedRatherThanNamed() {
        // The bounds are read off rewrite-spring 6.31.0: boot2 carries 2_0 to 2_7, boot3 carries
        // 3_0 to 3_5, boot4 carries 4_0. A name outside those is a recipe that does not exist,
        // which the Gradle actuator fails loudly on and the Maven one calls a success that changed
        // nothing. That second behaviour is why guessing has to happen here and not in a prompt.
        for (String beyond : new String[] {"2.8.0", "3.6.0", "4.1.0", "5.0.0"}) {
            assertEquals("", Rewrites.migrationFor("org.springframework.boot", beyond),
                    beyond + " has no free migration recipe");
        }
        assertEquals("", Rewrites.migrationFor("org.postgresql", "42.7.2"),
                "and nothing outside Spring Boot is covered, which it says rather than inventing");
        assertEquals("", Rewrites.migrationFor("org.springframework.boot", "three"));
        assertEquals("", Rewrites.migrationFor("org.springframework.boot", "3"),
                "a major with no minor names no line");
    }

    @Test
    void whatAProjectDeclaresIsFoundInEitherDialect(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("settings.gradle"), "rootProject.name = 'x'");
        Files.writeString(ws.resolve("build.gradle"), """
                plugins {
                    id 'org.springframework.boot' version '2.7.3'
                }
                dependencies {
                    implementation 'com.google.guava:guava:30.1-jre'
                }
                """);

        // The plugins block writes the id alone, with no artifactId anywhere, which is why the
        // lookup accepts the group on its own as well as the full coordinate.
        assertEquals(List.of("2.7.3"), Declared.valuesFor(ws, "org.springframework.boot"));
        assertEquals(List.of("30.1-jre"), Declared.valuesFor(ws, "com.google.guava:guava"));
        assertTrue(Declared.valuesFor(ws, "org.projectlombok:lombok").isEmpty(),
                "and something the project does not declare reads as nothing, not as zero");
    }

    @Test
    void theScriptTheHarnessWritesDoesNotReadBackAsTheAgentsWork(@TempDir Path ws)
            throws IOException {
        // Tree.diff() lists untracked files, because a whole class of correct migration is a new
        // file. The recipe document and the init script are the harness's own, and counting them
        // would report every run as having changed the working tree, which is the exact signal
        // Rewrites.reported() uses to decide whether anything happened.
        Files.createDirectories(ws.resolve(".git/info"));
        new Tree(ws, null).excludeBuildOutput();

        String exclude = Files.readString(ws.resolve(".git/info/exclude"));
        assertTrue(exclude.contains("rewrite.yml"), exclude);
        assertTrue(exclude.contains(Migrate.INIT), exclude);
    }
}
