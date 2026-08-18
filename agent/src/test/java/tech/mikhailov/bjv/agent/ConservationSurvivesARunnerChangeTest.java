package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A bump moves the build tooling, and the tooling renders test names. qaware/cloud-cost-fitness went
 * from Gradle 6.8.1 to 7.6 as part of its own migration, its 47 Spock tests all still passed, and
 * the gate reported 23 of them lost. Conservation has to survive the thing the bump does.
 */
class ConservationSurvivesARunnerChangeTest {

    private static Set<String> of(String... k) {
        return new LinkedHashSet<>(java.util.List.of(k));
    }

    @Test
    void aParameterisedFeatureIsNotLostWhenItsRenderedParametersChange() {
        // Same four iterations, rendered differently by the two runners.
        Set<String> before = of(
                "de.qaware.cloud.aws.converter.RequestConverterSpec#creates date intervals"
                        + " [timeRange: LAST_6_MONTHS, start: 2020-04-10, #0]",
                "de.qaware.cloud.aws.converter.RequestConverterSpec#creates date intervals"
                        + " [timeRange: LAST_30_DAYS, start: 2020-09-10, #1]");
        Set<String> after = of(
                "de.qaware.cloud.aws.converter.RequestConverterSpec#creates date intervals [0]",
                "de.qaware.cloud.aws.converter.RequestConverterSpec#creates date intervals [1]");
        assertEquals(0, Gate.lost(before, after), "renaming an iteration is not losing a test");
    }

    @Test
    void anIterationThatActuallyDisappearsIsStillCounted() {
        Set<String> before = of(
                "a.Spec#creates date intervals [x, #0]",
                "a.Spec#creates date intervals [y, #1]",
                "a.Spec#creates date intervals [z, #2]");
        Set<String> after = of(
                "a.Spec#creates date intervals [0]",
                "a.Spec#creates date intervals [1]");
        assertEquals(1, Gate.lost(before, after), "three iterations in, two out, one lost");
    }

    @Test
    void twoSpecsSharingAFeatureNameDoNotCoverForEachOther() {
        // Without the class in the key, B's survivor would mask A's loss.
        Set<String> before = of("a.ASpec#converts correctly", "b.BSpec#converts correctly");
        Set<String> after = of("b.BSpec#converts correctly");
        assertEquals(1, Gate.lost(before, after));
    }

    @Test
    void anOrdinaryLossIsUnaffected() {
        Set<String> before = of("a.Spec#one", "a.Spec#two", "a.Spec#three");
        Set<String> after = of("a.Spec#one", "a.Spec#three");
        assertEquals(1, Gate.lost(before, after));
    }
}
