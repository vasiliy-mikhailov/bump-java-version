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
 * A REPOSITORY IS ITS MODULES, AND THE GATE HAS BEEN JUDGING THE UNION.
 *
 * <p>The measured failure this replaces: {@link Pins} concatenated every build file in the tree and
 * returned the first version it matched anywhere, so a floor could read as met while most of the
 * project sat below it. Sixteen of twenty-seven multi-module repositories in this corpus carry some
 * package at more than one version, so the module a pin check answered about was chosen by
 * filesystem walk order.
 */
class ARepoIsItsModulesTest {

    @Test
    void aSingleModuleRepoIsExactlyItsRoot(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("pom.xml"), "<project><artifactId>solo</artifactId></project>");

        List<Modules.Module> modules = Modules.of(ws);

        assertEquals(1, modules.size(), "no aggregation, no split: " + modules);
        assertTrue(modules.get(0).isRoot());
        // Everything downstream loops rather than branching on whether a project aggregates.
        assertEquals(ws, modules.get(0).in(ws));
    }

    @Test
    void aggregatedModulesComeOutParentFirst(@TempDir Path ws) throws IOException {
        pom(ws, "<modules><module>common</module><module>web</module></modules>");
        pom(dir(ws, "common"), "");
        pom(dir(ws, "web"), "");

        assertEquals(List.of("", "common", "web"),
                Modules.of(ws).stream().map(Modules.Module::path).toList(),
                "a child referencing a parent property is not reasoned about before the parent");
    }

    @Test
    void aggregationIsFollowedAllTheWayDown(@TempDir Path ws) throws IOException {
        pom(ws, "<modules><module>services</module></modules>");
        pom(dir(ws, "services"), "<modules><module>orders</module></modules>");
        pom(dir(ws, "services/orders"), "");

        assertEquals(List.of("", "services", "services/orders"),
                Modules.of(ws).stream().map(Modules.Module::path).toList());
    }

    @Test
    void aDeclaredModuleWithNoDirectoryIsNotAModule(@TempDir Path ws) throws IOException {
        // Profiles routinely declare modules that are not checked in, and a path that does not
        // exist cannot be gated, pinned, or bumped.
        pom(ws, "<modules><module>real</module><module>ghost</module></modules>");
        pom(dir(ws, "real"), "");

        assertEquals(List.of("", "real"),
                Modules.of(ws).stream().map(Modules.Module::path).toList());
    }

    @Test
    void aCycleInTheAggregationTerminates(@TempDir Path ws) throws IOException {
        // Rare, but a non-terminating walk when it happens.
        pom(ws, "<modules><module>a</module></modules>");
        pom(dir(ws, "a"), "<modules><module>../a</module></modules>");

        assertEquals(List.of("", "a"), Modules.of(ws).stream().map(Modules.Module::path).toList());
    }

    @Test
    void gradleStatesTheWholeSetInOnePlace(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("settings.gradle.kts"), """
                rootProject.name = "thing"
                include(":core")
                include(":app:mobile")
                """);
        Files.createDirectories(ws.resolve("core"));
        Files.createDirectories(ws.resolve("app/mobile"));

        assertEquals(List.of("", "core", "app/mobile"),
                Modules.of(ws).stream().map(Modules.Module::path).toList());
    }

    @Test
    void aModulesBuildFilesStopWhereTheNextModuleBegins(@TempDir Path ws) throws IOException {
        pom(ws, "<modules><module>common</module></modules>");
        pom(dir(ws, "common"), "");
        List<Modules.Module> all = Modules.of(ws);

        List<Path> root = Modules.buildFilesOf(ws, all.get(0), all);
        List<Path> common = Modules.buildFilesOf(ws, all.get(1), all);

        assertEquals(1, root.size(), "the root owns its own pom and not the child's: " + root);
        assertEquals(ws.resolve("pom.xml"), root.get(0));
        assertEquals(List.of(ws.resolve("common/pom.xml")), common);
    }

    @Test
    void generatedOutputIsNotABuildFile(@TempDir Path ws) throws IOException {
        pom(ws, "");
        Files.createDirectories(ws.resolve("target/classes"));
        Files.writeString(ws.resolve("target/pom.xml"), "<project/>");

        List<Modules.Module> all = Modules.of(ws);
        List<Path> files = Modules.buildFilesOf(ws, all.get(0), all);

        assertEquals(1, files.size(), "a pom copied into target is output, not source: " + files);
        assertFalse(files.get(0).toString().contains("/target/"));
    }

    private static Path dir(Path ws, String name) throws IOException {
        return Files.createDirectories(ws.resolve(name));
    }

    private static void pom(Path at, String body) throws IOException {
        Files.writeString(at.resolve("pom.xml"),
                "<project><artifactId>" + at.getFileName() + "</artifactId>" + body + "</project>");
    }
}
