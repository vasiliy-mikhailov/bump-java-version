package tech.mikhailov.bjv.agent;

/**
 * WHAT EACH HOP PINS, WRITTEN OUT, ONE LIST PER HOP.
 *
 * <p>This was a table of rules with a threshold on each row, and the version for a target was
 * whichever row had the highest threshold that still applied. That is a line of code and a paragraph
 * of explanation, and it meant nobody could answer "what does an 11-to-17 bump pin?" without running
 * it. Four lists answer that by being read.
 *
 * <p>THE LIST IS THE PROMPT AND NOTHING ELSE READS IT. It is handed to the pin planners verbatim
 * and they decide, against what {@code declared_versions} reports, which floors a project sits
 * below. Nothing parses these lines.
 *
 * <p>It used to. A positional split turned each line into a record, and those records both drove the
 * check tool and decided whether an agent was shown the list at all. The split looked for
 * {@code org.springframework.boot:spring-boot} — an artifact no application declares, since a Maven
 * project inherits the starter parent or imports the BOM — concluded the floor was met, and skipped
 * the phase. Every Spring project in the corpus kept its Boot version while the log read "every pin
 * met". Two readers of one string, and the silent one won.
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
            [after] org.springframework.boot:spring-boot-starter-parent 2.7.18 or newer — the last of the 2.x line, and the ceiling here because Boot 3 needs Java 17. Run UpgradeSpringBoot_2_7 rather than writing a version: it carries newVersion 2.7.x, chains 2.0 through 2.6 beneath it, and moves the maven plugin and the BOM along with the parent. Six minor releases of renamed properties and withdrawn APIs sit between a Boot 2.1 project and 2.7, and a number typed into the parent block crosses none of them
            [after] org.springframework.boot:spring-boot-dependencies 2.7.18 or newer — the same floor for a project that imports the BOM instead of inheriting the parent; the same recipe raises it, and knows to override a managed version to do so
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
            [after] org.springframework.boot:spring-boot-starter-parent 3.5.16 or newer — Boot 3 needs Java 17, so it is reachable from here up, and the 3.5 line is where the free tooling ends since the only recipe for 4.1 is proprietary. Run UpgradeSpringBoot_3_5 on ANY project on the Boot 3 line whatever patch it declares, and never write a version into the parent block: the recipe carries newVersion 3.5.x, resolves the head of the line itself, and does nothing if already there. Being on 3.5 is NOT evidence of being current, and no version in the pom tells you either way. Measured on a project sitting at 3.5.4: the recipe took the parent to 3.5.16, Tomcat 10.1.43 to 10.1.55, jackson-databind 2.19.2 to 2.21.4, migrated fastjson 1.2.67 to fastjson2 2.0.64, and moved CRITICAL+HIGH from 24 to 1 over the same 129 packages, with all five tests still passing
            [after] org.springframework.boot:spring-boot-dependencies 3.5.16 — the same floor for a project that imports the BOM instead of inheriting the parent, reached the same way
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
            [after] org.springframework.boot:spring-boot-starter-parent 3.5.16 or newer — Boot 3 needs Java 17, so it is reachable from here up, and the 3.5 line is where the free tooling ends since the only recipe for 4.1 is proprietary. Run UpgradeSpringBoot_3_5 on ANY project on the Boot 3 line whatever patch it declares, and never write a version into the parent block: the recipe carries newVersion 3.5.x, resolves the head of the line itself, and does nothing if already there. Being on 3.5 is NOT evidence of being current, and no version in the pom tells you either way. Measured on a project sitting at 3.5.4: the recipe took the parent to 3.5.16, Tomcat 10.1.43 to 10.1.55, jackson-databind 2.19.2 to 2.21.4, migrated fastjson 1.2.67 to fastjson2 2.0.64, and moved CRITICAL+HIGH from 24 to 1 over the same 129 packages, with all five tests still passing
            [after] org.springframework.boot:spring-boot-dependencies 3.5.16 — the same floor for a project that imports the BOM instead of inheriting the parent, reached the same way
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
            [after] org.springframework.boot:spring-boot-starter-parent 3.5.16 or newer — Boot 3 needs Java 17, so it is reachable from here up, and the 3.5 line is where the free tooling ends since the only recipe for 4.1 is proprietary. Run UpgradeSpringBoot_3_5 on ANY project on the Boot 3 line whatever patch it declares, and never write a version into the parent block: the recipe carries newVersion 3.5.x, resolves the head of the line itself, and does nothing if already there. Being on 3.5 is NOT evidence of being current, and no version in the pom tells you either way. Measured on a project sitting at 3.5.4: the recipe took the parent to 3.5.16, Tomcat 10.1.43 to 10.1.55, jackson-databind 2.19.2 to 2.21.4, migrated fastjson 1.2.67 to fastjson2 2.0.64, and moved CRITICAL+HIGH from 24 to 1 over the same 129 packages, with all five tests still passing
            [after] org.springframework.boot:spring-boot-dependencies 3.5.16 — the same floor for a project that imports the BOM instead of inheriting the parent, reached the same way
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

}
