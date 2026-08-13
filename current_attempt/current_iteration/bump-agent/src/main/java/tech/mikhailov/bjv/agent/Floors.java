package tech.mikhailov.bjv.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * WHAT EACH HOP PINS, WRITTEN OUT, ONE LIST PER HOP.
 *
 * <p>This was a table of rules with a threshold on each row, and the version for a target was
 * whichever row had the highest threshold that still applied. That is a line of code and a paragraph
 * of explanation, and it meant nobody could answer "what does an 11-to-17 bump pin?" without running
 * it. Four lists answer that by being read.
 *
 * <p>THE LIST IS THE PROMPT. The preparer and its critic are handed this text verbatim, and the
 * deterministic pass parses the same text to apply it, so the version an agent is told and the
 * version the code writes are the same characters rather than two things kept in step.
 *
 * <p>The cost is honest: lombok appears in all four lists, so raising it is four edits rather than
 * one. They sit adjacent in one file where a reader can see them disagree, which is a different risk
 * from the one this replaced, where a number was right in the code and stale in the instructions.
 *
 * <p>Format is {@code group:artifact version — why}. The reason travels with the pin because a floor
 * without one is indistinguishable from a superstition, and these accumulate. Every version was
 * measured on this corpus rather than read off a compatibility table.
 */
final class Floors {

    private static final String TO_11 = """
            org.projectlombok:lombok 1.18.30 — older Lombok reads javac internals that moved, and dies with ExceptionInInitializerError on TypeTags, which never names Lombok
            net.bytebuddy:byte-buddy 1.14.12 — refuses a class file major it does not know, which Mockito reports as being unable to mock a class
            net.bytebuddy:byte-buddy-agent 1.14.12 — moves with byte-buddy; a split pair fails in the same place
            org.mockito:mockito-core 5.18.0 — carries the Byte Buddy floor transitively
            com.tngtech.archunit:archunit 1.4.1 — reads bytecode directly and rejects a major it predates
            com.tngtech.archunit:archunit-junit5 1.4.1 — moves with archunit
            org.jacoco:jacoco-maven-plugin 0.8.15 — instruments bytecode and refuses a major it predates
                        javax.xml.bind:jaxb-api 2.3.1 — JEP 320 removed the Java EE modules from the JDK; a project that used them needs them as dependencies now
            org.glassfish.jaxb:jaxb-runtime 2.3.1 — the runtime half of the same removal
            javax.annotation:javax.annotation-api 1.3.2 — removed by the same JEP
            org.hamcrest:hamcrest 2.2 — the old hamcrest-core split, which surefire stops resolving
            org.junit.platform:junit-platform-launcher 1.10.2 — newer surefire needs it declared rather than inherited
            org.apache.maven.plugins:maven-compiler-plugin 3.13.0 — older compiler plugins reject the target outright
            [after] org.springframework.boot:spring-boot 2.7.18 — the last of the 2.x line, and the ceiling here: Boot 3 and 4 both require Java 17
            """;

    private static final String TO_17 = """
            org.projectlombok:lombok 1.18.30 — older Lombok reads javac internals that moved, and dies with ExceptionInInitializerError on TypeTags, which never names Lombok
            net.bytebuddy:byte-buddy 1.14.12 — refuses a class file major it does not know, which Mockito reports as being unable to mock a class
            net.bytebuddy:byte-buddy-agent 1.14.12 — moves with byte-buddy; a split pair fails in the same place
            org.mockito:mockito-core 5.18.0 — carries the Byte Buddy floor transitively
            com.tngtech.archunit:archunit 1.4.1 — reads bytecode directly and rejects a major it predates
            com.tngtech.archunit:archunit-junit5 1.4.1 — moves with archunit
            org.jacoco:jacoco-maven-plugin 0.8.15 — instruments bytecode and refuses a major it predates
            org.gradle:gradle-wrapper 7.6 — older wrappers cannot run the toolchain this target needs
                        javax.xml.bind:jaxb-api 2.3.1 — JEP 320 removed the Java EE modules from the JDK; a project that used them needs them as dependencies now
            org.glassfish.jaxb:jaxb-runtime 2.3.1 — the runtime half of the same removal
            javax.annotation:javax.annotation-api 1.3.2 — removed by the same JEP
            org.hamcrest:hamcrest 2.2 — the old hamcrest-core split, which surefire stops resolving
            org.junit.platform:junit-platform-launcher 1.10.2 — newer surefire needs it declared rather than inherited
            org.apache.maven.plugins:maven-compiler-plugin 3.13.0 — older compiler plugins reject the target outright
            [after] org.springframework.boot:spring-boot 4.1.0 — Boot 4.1 declares java.version 17, so it is reachable from here up; the recipe stops at UpgradeSpringBoot_4_0 and this floor lifts the patch
            """;

    private static final String TO_21 = """
            org.projectlombok:lombok 1.18.30 — older Lombok reads javac internals that moved, and dies with ExceptionInInitializerError on TypeTags, which never names Lombok
            net.bytebuddy:byte-buddy 1.14.12 — refuses a class file major it does not know, which Mockito reports as being unable to mock a class
            net.bytebuddy:byte-buddy-agent 1.14.12 — moves with byte-buddy; a split pair fails in the same place
            org.mockito:mockito-core 5.18.0 — carries the Byte Buddy floor transitively
            com.tngtech.archunit:archunit 1.4.1 — reads bytecode directly and rejects a major it predates
            com.tngtech.archunit:archunit-junit5 1.4.1 — moves with archunit
            org.jacoco:jacoco-maven-plugin 0.8.15 — instruments bytecode and refuses a major it predates
            org.gradle:gradle-wrapper 8.10.2 — older wrappers cannot run the toolchain this target needs
            org.apache.tomcat.embed:tomcat-embed-core 9.0.105 — the newest 9.0 the mirror carries, and the fewest CVEs of that line; only where Spring is absent, since Boot brings a newer Tomcat of its own
                        javax.xml.bind:jaxb-api 2.3.1 — JEP 320 removed the Java EE modules from the JDK; a project that used them needs them as dependencies now
            org.glassfish.jaxb:jaxb-runtime 2.3.1 — the runtime half of the same removal
            javax.annotation:javax.annotation-api 1.3.2 — removed by the same JEP
            org.hamcrest:hamcrest 2.2 — the old hamcrest-core split, which surefire stops resolving
            org.junit.platform:junit-platform-launcher 1.10.2 — newer surefire needs it declared rather than inherited
            org.apache.maven.plugins:maven-compiler-plugin 3.13.0 — older compiler plugins reject the target outright
            [after] org.springframework.boot:spring-boot 4.1.0 — Boot 4.1 declares java.version 17, so it is reachable here; the recipe stops at UpgradeSpringBoot_4_0 and this floor lifts the patch
            """;

    private static final String TO_25 = """
            org.projectlombok:lombok 1.18.46 — the 1.18.30 line does not understand the JDK 25 AST
            net.bytebuddy:byte-buddy 1.17.6 — the first line that knows class file 69
            net.bytebuddy:byte-buddy-agent 1.17.6 — moves with byte-buddy
            org.mockito:mockito-core 5.18.0 — carries the Byte Buddy floor transitively
            com.tngtech.archunit:archunit 1.4.1 — reads bytecode directly and rejects a major it predates
            com.tngtech.archunit:archunit-junit5 1.4.1 — moves with archunit
            org.jacoco:jacoco-maven-plugin 0.8.15 — instruments bytecode and refuses a major it predates
            org.jetbrains.kotlin:kotlin 2.3.20 — every Kotlin 1.x either crashes on JDK 25 or silently falls back below the target, which the gate reads as an unraised bump
            org.gradle:gradle-wrapper 9.1.0 — older wrappers cannot run the toolchain this target needs
            org.apache.tomcat.embed:tomcat-embed-core 9.0.105 — the newest 9.0 the mirror carries, and the fewest CVEs of that line; only where Spring is absent, since Boot brings a newer Tomcat of its own
                        javax.xml.bind:jaxb-api 2.3.1 — JEP 320 removed the Java EE modules from the JDK; a project that used them needs them as dependencies now
            org.glassfish.jaxb:jaxb-runtime 2.3.1 — the runtime half of the same removal
            javax.annotation:javax.annotation-api 1.3.2 — removed by the same JEP
            org.hamcrest:hamcrest 2.2 — the old hamcrest-core split, which surefire stops resolving
            org.junit.platform:junit-platform-launcher 1.10.2 — newer surefire needs it declared rather than inherited
            org.apache.maven.plugins:maven-compiler-plugin 3.13.0 — older compiler plugins reject the target outright
            [after] org.springframework.boot:spring-boot 4.1.0 — Boot 4.1 declares java.version 17, so it is reachable here; the recipe stops at UpgradeSpringBoot_4_0 and this floor lifts the patch
            """;

    /**
     * WHICH SIDE OF THE JDK CHANGE A PIN BELONGS ON.
     *
     * <p>Two constraints pull opposite ways. Lombok must move BEFORE the JDK, because a Lombok that
     * cannot read the new class file kills javac before anything else runs. Spring Boot must move
     * AFTER, because Boot 4.1 declares java.version 17 in its own pom and cannot be resolved by a
     * project still on 11. A line is marked {@code [after]} when its version requires the target
     * JDK; everything else installs happily on the old one and is needed by the new one.
     */
    static String before(int target) {
        return forTarget(target).lines()
                .filter(l -> !l.isBlank() && !l.strip().startsWith("[after]"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** The pins that only work once the JDK has moved. */
    static String after(int target) {
        return forTarget(target).lines()
                .filter(l -> l.strip().startsWith("[after]"))
                .map(l -> l.strip().substring("[after]".length()).strip())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /** One pinned dependency, parsed back out of the line an agent is shown. */
    record Floor(String group, String artifact, String version, String why) {

        String coordinates() {
            return group + ":" + artifact;
        }
    }

    private Floors() {
    }

    /** The list for a target, exactly as the preparer is handed it. */
    static String forTarget(int target) {
        if (target >= 25) {
            return TO_25;
        }
        if (target >= 21) {
            return TO_21;
        }
        if (target >= 17) {
            return TO_17;
        }
        return TO_11;
    }

    /** The same list, parsed, for the code that applies it. */
    static List<Floor> at(int target) {
        List<Floor> out = new ArrayList<>();
        for (String line : forTarget(target).lines().toList()) {
            String[] head = line.strip().replaceFirst("^\\[after\\]\\s*", "").split(" ", 3);
            if (head.length < 2 || !head[0].contains(":")) {
                continue;
            }
            String[] coordinates = head[0].split(":", 2);
            out.add(new Floor(coordinates[0], coordinates[1], head[1],
                    head.length > 2 ? head[2].replaceFirst("^—\\s*", "") : ""));
        }
        return out;
    }

    /**
     * What this target pins for an artifact, or empty if this hop does not pin it.
     *
     * <p>Read out of the same list the agent is shown, so there is no second place to keep in step.
     */
    static String version(String artifact, int target) {
        return at(target).stream()
                .filter(f -> f.artifact().equals(artifact))
                .map(Floor::version)
                .findFirst()
                .orElse("");
    }

    /** Every distinct pin across every hop, for a reader who wants the whole picture at once. */
    static List<Floor> all() {
        List<Floor> out = new ArrayList<>();
        for (int target : new int[] {11, 17, 21, 25}) {
            for (Floor f : at(target)) {
                if (out.stream().noneMatch(x -> x.coordinates().equals(f.coordinates())
                        && x.version().equals(f.version()))) {
                    out.add(f);
                }
            }
        }
        return out;
    }
}
