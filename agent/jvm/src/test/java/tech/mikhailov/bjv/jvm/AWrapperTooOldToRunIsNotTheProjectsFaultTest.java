package tech.mikhailov.bjv.jvm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DEADLOCK WITH ONE WAY OUT, AND THE COST OF TAKING IT WRONGLY.
 *
 * <p>The Gradle half of a recipe runs on the project's own wrapper, so a wrapper too old to start
 * under the target JDK fails every recipe the phase will ever run. The wrapper is itself a floor,
 * and raising it needs the recipe it cannot run. Measured on one bump: the first call was bump_line
 * onto Spring Boot, which would have carried all eighteen of that bump's outstanding floors at
 * once, and it died on the wrapper along with the twenty-nine calls after it. Both write tools then
 * refused the wrapper itself, correctly, because 8.0 to 8.10.2 is a line move. Nothing was wrong
 * with the project.
 *
 * <p>The image carries a Gradle of its own, so there is somewhere else to run. What this guards is
 * the scoping: each attempt holds a forty-five minute leash, so a second one is worth taking only
 * where the wrapper never started, and never for a recipe that ran and failed on its own merits.
 */
class AWrapperTooOldToRunIsNotTheProjectsFaultTest {

    @Test
    void theShapesAWrapperDiesInAreRecognised() {
        // The measured one, from a Gradle 8.0 wrapper handed JDK 21.
        assertTrue(Migrate.wrapperCannotStart(
                "FAILURE: Build failed with an exception.\n"
                + "* What went wrong:\nCould not open cp_init generic class cache for "
                + "initialization script '/work/bjv-rewrite.init.gradle'.\n"
                + "> BUG! exception in phase 'semantic analysis'"),
                "the wrapper bootstrap failure this exists for");

        assertTrue(Migrate.wrapperCannotStart("Unsupported class file major version 65"),
                "an old Groovy in an old wrapper meeting a new class file");
        assertTrue(Migrate.wrapperCannotStart("Could not determine java version from '21.0.4'"),
                "the shape a wrapper too old to parse the JDK version dies in");
    }

    @Test
    void anOrdinaryRecipeFailureBuysNoSecondAttempt() {
        // FORTY-FIVE MINUTES EACH, so this is the assertion that keeps the fix from costing more
        // than it returns. None of these would go differently on another Gradle.
        assertFalse(Migrate.wrapperCannotStart(
                "Recipe validation error in org.openrewrite.java.spring.NoRepoAnnotation: "
                + "recipe does not exist"),
                "a name that does not resolve fails the same way anywhere");
        assertFalse(Migrate.wrapperCannotStart(
                "* What went wrong:\nExecution failed for task ':compileJava'.\n"
                + "> Compilation failed; see the compiler error output for details."),
                "a project that will not compile is not a wrapper that will not start");
        assertFalse(Migrate.wrapperCannotStart(""), "an empty log is not evidence of anything");
        assertFalse(Migrate.wrapperCannotStart(null), "and neither is no log at all");
    }
}
