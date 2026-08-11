package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE MECHANIZED TROUBLESHOOTING TABLE — the reflect loop's enumerable half.
 *
 * <p>Rung-1 decomposed the reflect loop's value: roughly half of what iteration recovers is
 * signature -> known fix, no judgement involved. That half belongs here, where it costs no tokens
 * and cannot be stochastically fumbled. What this table cannot match falls through to the fixer
 * agent, which is exactly the residue a model is FOR.
 *
 * <p>Every row's fix carries the evidence that put it here. A row nobody can trace to a measured
 * failure is folklore, and folklore is how the 11->17 skill briefly told agents to floor Lombok to
 * a JaCoCo version.
 */
final class Walls {

    /** What one turn of the loop did, or that it recognised nothing. */
    record Turn(boolean fixed, String what) {
    }

    private final Path ws;
    private final Set<String> applied = new LinkedHashSet<>();

    Walls(Path ws) {
        this.ws = ws;
    }

    Turn match(String log, int target) throws IOException {
        // JEP 320: the java.xml.bind/annotation modules left the JDK at 11. Proven pair on
        // canvas-api-client-feign: floor + annotation-api took 0 -> 903 tests green.
        if (once("jep320") && Pattern.compile(
                "package javax\\.(xml\\.bind|annotation|activation) does not exist").matcher(log).find()) {
            Pom.addDependency(ws, "javax.xml.bind", "jaxb-api", "2.3.1", null);
            Pom.addDependency(ws, "org.glassfish.jaxb", "jaxb-runtime", "2.3.1", "runtime");
            Pom.addDependency(ws, "javax.annotation", "javax.annotation-api", "1.3.2", null);
            return new Turn(true, "jep320: jaxb-api + jaxb-runtime + javax.annotation-api");
        }

        // Strong encapsulation: the error names the package; the fix is that exact package. The
        // opens cascade (mosip surfaced four, one per rebuild), so the loop will be back here.
        Matcher opens = Pattern.compile("module java\\.base does not \"opens ([a-z.]+)\"").matcher(log);
        if (opens.find() && once("opens:" + opens.group(1))) {
            Set<String> pkgs = new LinkedHashSet<>(List.of("java.lang", "java.util"));
            opens.reset();
            while (opens.find()) {
                pkgs.add(opens.group(1));
            }
            List<String> tokens = new ArrayList<>();
            for (String p : pkgs) {
                tokens.add("--add-opens=java.base/" + p + "=ALL-UNNAMED");
            }
            Pom.extendArgLine(ws, tokens);
            return new Turn(true, "add-opens: " + pkgs);
        }

        // Lombok, by its two javac signatures; neither names lombok on the error line. The floor
        // goes in as a dependencyManagement entry, the one placement the A/B proved beats imported
        // BOMs and covers transitive-only lombok.
        if (once("lombok") && Pattern.compile(
                "ExceptionInInitializerError: com\\.sun\\.tools\\.javac\\.code\\.TypeTags"
                        + "|JCTree\\$JCImport does not have member field").matcher(log).find()) {
            String v = target >= 25 ? "1.18.46" : "1.18.30";
            Pom.manage(ws, "org.projectlombok", "lombok", v);
            return new Turn(true, "lombok floor " + v + " via dependencyManagement");
        }

        if (once("mockito") && Pattern.compile(
                "Mockito cannot mock this class|Cannot define class using reflection"
                        + "|Could not initialize plugin.*MockMaker").matcher(log).find()) {
            String bb = target >= 25 ? "1.17.6" : "1.14.12";
            Pom.manage(ws, "net.bytebuddy", "byte-buddy", bb);
            Pom.manage(ws, "net.bytebuddy", "byte-buddy-agent", bb);
            Pom.manage(ws, "org.mockito", "mockito-core", "5.18.0");
            return new Turn(true, "byte-buddy " + bb + " + mockito-core 5.18.0");
        }

        // A JUnit 4->5 migration removes junit:junit, and hamcrest plus the platform launcher only
        // ever arrived transitively through it.
        if (once("hamcrest") && Pattern.compile(
                "package org\\.hamcrest does not exist").matcher(log).find()) {
            Pom.addDependency(ws, "org.hamcrest", "hamcrest", "2.2", "test");
            return new Turn(true, "hamcrest 2.2 (removed transitively with junit:junit)");
        }
        if (once("launcher") && log.contains("junit-platform-launcher")) {
            Pom.addDependency(ws, "org.junit.platform", "junit-platform-launcher", "1.10.2", "test");
            return new Turn(true, "junit-platform-launcher");
        }

        if (once("compiler") && Pattern.compile(
                "invalid flag: --release|release version \\d+ not supported|invalid target release")
                .matcher(log).find()) {
            Pom.pluginVersion(ws, "maven-compiler-plugin", "3.13.0");
            return new Turn(true, "maven-compiler-plugin 3.13.0");
        }

        // ArchUnit imports ZERO classes below 1.4.1 on major 69 while the build stays green; the
        // count matching is what makes it look like a conservation flake. 1.4.1 measured, 1.4.0 not enough.
        if (once("archunit") && log.toLowerCase().contains("archunit")
                && log.contains("Unsupported class file major version")) {
            Pom.manage(ws, "com.tngtech.archunit", "archunit", "1.4.1");
            Pom.manage(ws, "com.tngtech.archunit", "archunit-junit5", "1.4.1");
            return new Turn(true, "archunit 1.4.1 (first v69-capable)");
        }

        // Kotlin: every 1.x crashes or falls back below the target; only >= 2.3.x emits current
        // JVM targets. Measured floor 2.3.20.
        if (once("kotlin") && Pattern.compile(
                "Unknown JVM target version: 2[15]|falling back to Kotlin JVM_21"
                        + "|Kotlin does not yet support 25").matcher(log).find()) {
            Pom.setPropertyEverywhere(ws, "kotlin.version", "2.3.20");
            return new Turn(true, "kotlin.version 2.3.20 in every pom");
        }

        return new Turn(false, "no known wall");
    }

    private boolean once(String key) {
        return !applied.contains(key) && applied.add(key);
    }

    /** Which rows have fired, for the trace and for the fixer's brief. */
    String appliedSoFar() {
        return String.join(", ", applied);
    }

    static boolean isPom(Path p) {
        return p.getFileName() != null && p.getFileName().toString().equals("pom.xml");
    }

    static List<Path> poms(Path ws) throws IOException {
        try (var s = Files.walk(ws)) {
            return s.filter(Walls::isPom)
                    .filter(p -> !p.toString().contains("/target/")
                            && !p.toString().contains("/node_modules/"))
                    .toList();
        }
    }
}
