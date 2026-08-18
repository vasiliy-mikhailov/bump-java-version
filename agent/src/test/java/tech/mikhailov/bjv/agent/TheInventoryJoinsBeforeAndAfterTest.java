package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The count says whether the bump moved the number; the inventory says what it moved. Both scans
 * have to name the same package the same way or the join is meaningless.
 */
class TheInventoryJoinsBeforeAndAfterTest {

    private static final String REPORT = """
            {"SchemaVersion":2,"Results":[
              {"Target":"core/target/dependency/jackson-databind-2.11.0.jar","Type":"jar",
               "Packages":[{"Name":"com.fasterxml.jackson.core:jackson-databind","Version":"2.11.0",
                            "FilePath":"core/target/dependency/jackson-databind-2.11.0.jar"}],
               "Vulnerabilities":[
                 {"VulnerabilityID":"CVE-1","PkgName":"com.fasterxml.jackson.core:jackson-databind",
                  "PkgPath":"core/target/dependency/jackson-databind-2.11.0.jar",
                  "InstalledVersion":"2.11.0","Severity":"CRITICAL"},
                 {"VulnerabilityID":"CVE-2","PkgName":"com.fasterxml.jackson.core:jackson-databind",
                  "PkgPath":"core/target/dependency/jackson-databind-2.11.0.jar",
                  "InstalledVersion":"2.11.0","Severity":"HIGH"}]},
              {"Target":"core/target/dependency/guava-30.0.jar","Type":"jar",
               "Packages":[{"Name":"com.google.guava:guava","Version":"30.0",
                            "FilePath":"core/target/dependency/guava-30.0.jar"}]}]}
            """;

    @Test
    void everyResolvedPackageIsListed_withItsVersionAndItsShareOfTheCves() {
        Security.Scan s = Security.parse(REPORT);
        assertEquals(2, s.inventory().size(), "a clean package belongs in the list too");
        Security.Pkg jackson = s.inventory().get(0);
        assertEquals("com.fasterxml.jackson.core:jackson-databind", jackson.name());
        assertEquals("2.11.0", jackson.version());
        assertEquals(2, jackson.cves(), "the worst offender sorts first and carries its count");
        Security.Pkg guava = s.inventory().get(1);
        assertEquals("30.0", guava.version());
        assertEquals(0, guava.cves());
        assertEquals("core", guava.module());
    }

    /** The same project after a framework BOM moved jackson and cleared both its findings. */
    private static final String AFTER = """
            {"SchemaVersion":2,"Results":[
              {"Target":"core/target/dependency/jackson-databind-2.15.4.jar","Type":"jar",
               "Packages":[{"Name":"com.fasterxml.jackson.core:jackson-databind","Version":"2.15.4",
                            "FilePath":"core/target/dependency/jackson-databind-2.15.4.jar"}],
               "Vulnerabilities":[]},
              {"Target":"core/target/dependency/guava-30.0.jar","Type":"jar",
               "Packages":[{"Name":"com.google.guava:guava","Version":"30.0",
                            "FilePath":"core/target/dependency/guava-30.0.jar"}]}]}
            """;

    @Test
    void aLiftedDependencyIsVisibleAsAVersionChangeAndACveDrop() {
        Security.Scan before = Security.parse(REPORT);
        Security.Scan after = Security.parse(AFTER);
        assertEquals(2, before.total());
        assertEquals(0, after.total());

        Security.Pkg was = before.inventory().stream()
                .filter(p -> p.name().contains("jackson")).findFirst().orElseThrow();
        Security.Pkg now = after.inventory().stream()
                .filter(p -> p.name().contains("jackson")).findFirst().orElseThrow();
        assertEquals("2.11.0", was.version());
        assertEquals("2.15.4", now.version());
        assertEquals(2, was.cves());
        assertEquals(0, now.cves(), "the lift shows as a drop on that package's own row");

        // The join key must survive the version change, or the table shows a removal and an
        // addition instead of one dependency that moved.
        assertEquals(was.key(), now.key());

        // And the delta must read it as a real clearance, not as a collapsed resolve.
        Security.Delta d = Security.compare(before, after);
        assertTrue(d.valid(), d.why());
        assertEquals(2, d.cleared());
    }
}
