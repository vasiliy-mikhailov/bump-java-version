package tech.mikhailov.bjv.bump;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A CAMPAIGN THAT RUNS IS A CAMPAIGN THAT GETS ITS OWN KEY, OR A RESUME REPLAYS THE WRONG ONE.
 *
 * <p>This is the test that was missing while 338 others passed. The key was the inner loop index,
 * reset on every call, and the module gate calls that method once per TURN: two distinct keys for
 * up to six campaigns. Turns two and three replayed turn one, and because the replayed answer says
 * a step landed, the gate stayed open and spent its remaining turns recompiling an untouched tree
 * while still paying a repair planner and a repair verifier each time.
 *
 * <p>So it broke runs that never resumed at all, which is why a journal test could not have caught
 * it: nothing was being resumed. It asserts on the COUNT of distinct keys rather than their
 * spelling, so a rename does not make it lie.
 */
class ACampaignKeyTest {

    private static Object bumpForShape() throws Exception {
        Constructor<Bump> c = Bump.class.getDeclaredConstructor(
                Path.class, String.class, tech.mikhailov.bjv.engine.Trace.class,
                tech.mikhailov.bjv.engine.Journal.class, boolean.class);
        c.setAccessible(true);
        return c.newInstance(Path.of("."), "shape|shape|17|21", null, null, false);
    }

    private static String key(Object bump, String module) throws Exception {
        Method m = Bump.class.getDeclaredMethod("nextCampaignKey", String.class);
        m.setAccessible(true);
        return (String) m.invoke(bump, module);
    }

    @Test
    void everyCampaignOfOneModuleGetsItsOwnKeyAcrossTheGateTurns() throws Exception {
        Object bump = bumpForShape();
        Set<String> seen = new LinkedHashSet<>();
        // Three gate turns, two campaigns each, which is what MODULE_TURNS and REASK allow.
        for (int turn = 0; turn < 3; turn++) {
            for (int campaign = 0; campaign < 2; campaign++) {
                seen.add(key(bump, "web"));
            }
        }
        assertEquals(6, seen.size(),
                "six campaigns ran, so six keys: with the defect this was 2 and four campaigns "
                        + "replayed answers from campaigns that were not theirs, in a run that "
                        + "never resumed. Saw: " + seen);
    }

    @Test
    void theCountResetsWhenTheWalkMovesToTheNextModule() throws Exception {
        Object bump = bumpForShape();
        key(bump, "web");
        key(bump, "web");
        String first = key(bump, "core");
        assertEquals("core#0", first,
                "a resume replays the same modules in the same order, so counting per module "
                        + "reproduces the same sequence; a bump-wide counter would not");
    }
}
