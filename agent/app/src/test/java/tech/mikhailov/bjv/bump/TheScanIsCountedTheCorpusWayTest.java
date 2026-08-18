package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scan feeds a reward and a comparison against a 1439-repo baseline, so it has to count what
 * the corpus counts: CRITICAL and HIGH only, attributed per module by the jar's path.
 */
class TheScanIsCountedTheCorpusWayTest {

    /** Real trivy rootfs shape: one Result per jar, the Target carrying the module path. */
    private static final String REPORT = """
            {"SchemaVersion":2,"Results":[
              {"Target":"core/target/dependency/jackson-databind-2.11.0.jar","Class":"lang-pkgs",
               "Type":"jar",
               "Packages":[{"Name":"com.fasterxml.jackson.core:jackson-databind","Version":"2.11.0",
                            "FilePath":"core/target/dependency/jackson-databind-2.11.0.jar"}],
               "Vulnerabilities":[
                 {"VulnerabilityID":"CVE-1","PkgName":"com.fasterxml.jackson.core:jackson-databind",
                  "PkgPath":"core/target/dependency/jackson-databind-2.11.0.jar",
                  "InstalledVersion":"2.11.0","FixedVersion":"2.13.2.1","Severity":"CRITICAL"}]},
              {"Target":"web/target/dependency/netty-handler-4.1.50.jar","Class":"lang-pkgs",
               "Type":"jar",
               "Packages":[{"Name":"io.netty:netty-handler","Version":"4.1.50",
                            "FilePath":"web/target/dependency/netty-handler-4.1.50.jar"}],
               "Vulnerabilities":[
                 {"VulnerabilityID":"CVE-2","PkgName":"io.netty:netty-handler",
                  "PkgPath":"web/target/dependency/netty-handler-4.1.50.jar",
                  "InstalledVersion":"4.1.50","FixedVersion":"4.1.68","Severity":"HIGH"},
                 {"VulnerabilityID":"CVE-3","PkgName":"io.netty:netty-handler",
                  "PkgPath":"web/target/dependency/netty-handler-4.1.50.jar",
                  "InstalledVersion":"4.1.50","Severity":"MEDIUM"}]}]}
            """;

    @Test
    void onlyCriticalAndHighCount_andModulesComeFromTheJarPath() {
        Security.Scan s = Security.parse(REPORT);
        assertTrue(s.measured());
        assertEquals(2, s.total(), "MEDIUM must not be counted");
        assertEquals(1, s.critical());
        assertEquals(1, s.high());
        assertEquals(1, s.perModule().get("core"));
        assertEquals(1, s.perModule().get("web"));
        assertEquals(2, s.packages());
    }

    @Test
    void aReportWithNoResultsIsNotACleanProject() {
        // Nothing resolved, so nothing was scanned. Reporting zero here would read as perfect.
        Security.Scan s = Security.parse("{\"SchemaVersion\":2,\"Results\":null}");
        assertFalse(s.measured());
        assertTrue(s.why().contains("nothing resolved"));
    }

    @Test
    void aCountThatFellBecauseAModuleVanishedIsNotAnImprovement() {
        Security.Scan before = Security.parse(REPORT);
        // The after scan lost the whole 'web' module: its build died before resolving.
        String shrunk = REPORT.replaceAll("(?s),\\s*\\{\"Target\":\"web/.*?\\]\\}", "]}");
        Security.Scan after = Security.parse(shrunk);
        Security.Delta d = Security.compare(before, after);
        assertFalse(d.valid(), "a vanished module must not read as a cleared vulnerability");
        assertTrue(d.why().contains("web") || d.why().contains("packages"), d.why());
    }

    @Test
    void arealClearanceIsCounted() {
        Security.Scan before = Security.parse(REPORT);
        // Same modules, same packages, one CVE genuinely gone.
        String fixed = REPORT.replace("\"Severity\":\"CRITICAL\"", "\"Severity\":\"LOW\"");
        Security.Scan after = Security.parse(fixed);
        Security.Delta d = Security.compare(before, after);
        assertTrue(d.valid(), d.why());
        assertEquals(1, d.cleared());
        assertEquals(2, d.before());
        assertEquals(1, d.after());
    }
}
