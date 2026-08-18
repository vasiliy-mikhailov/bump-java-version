package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A count is not a diagnosis.
 *
 * <p>The gate compared sets of test names and then reported only how many were gone. A
 * troubleshooter handed "4 of 7 tests no longer pass" guessed at the cause, invented a bean for a
 * captcha library, wrote {@code new} against an interface, and reported the resulting compile error
 * as proof that the dependency was incompatible with the JDK. The names were computed all along.
 */
class TheGateSaysWhichTestsItLostTest {

    @Test
    void theNamesComeBackNotJustTheCount() {
        Set<String> before = Set.of(
                "com.yu.utils.KaptchaTest#render",
                "com.yu.utils.KaptchaTest#validate",
                "com.yu.math.PrimeTest#isPrime",
                "com.yu.email.test.Test1#send");
        Set<String> after = Set.of(
                "com.yu.math.PrimeTest#isPrime",
                "com.yu.email.test.Test1#send");

        List<String> gone = Gate.missing(before, after);

        assertEquals(2, gone.size());
        assertEquals(List.of("com.yu.utils.KaptchaTest#render", "com.yu.utils.KaptchaTest#validate"),
                gone, "sorted, so a whole package disappearing reads as one block");
        assertEquals(gone.size(), Gate.lost(before, after), "the count still agrees with the names");
    }

    @Test
    void aConservationVerdictCarriesThemAndTheOthersCarryNothing() {
        Set<String> before = Set.of("A#one", "A#two");
        Gate.Verdict lost = Gate.decide(before, Set.of("A#one"), true, 21, 21);
        assertEquals("FAIL_test_conservation", lost.state());
        assertEquals(List.of("A#two"), lost.missing());

        Gate.Verdict pass = Gate.decide(before, before, true, 21, 21);
        assertTrue(pass.pass());
        assertTrue(pass.missing().isEmpty(), "a passing gate has nothing to name");

        Gate.Verdict low = Gate.decide(before, before, true, 17, 21);
        assertEquals("FAIL_target_not_bumped", low.state());
        assertTrue(low.missing().isEmpty(), "an unraised target lost no tests");
    }

    @Test
    void theDashboardCanStillParseTheTraceLineTheNamesAreAppendedTo() {
        // Dashboard.java reads the gate chip out of this line. The names go after the closing
        // paren precisely so that regex keeps matching; a reformat here silently empties a column.
        String line = "turn 1: FAIL_test_conservation (pre=7 lost=4 effective-target=21)"
                + "\n  com.yu.utils.KaptchaTest#render\n  ... and 3 more";
        Matcher m = Pattern.compile("pre=(\\d+) lost=(\\d+) effective-target=(-?\\d+)").matcher(line);
        assertTrue(m.find(), "the dashboard's own pattern must still find the counts");
        assertEquals("7", m.group(1));
        assertEquals("4", m.group(2));
        assertEquals("21", m.group(3));
    }
}
