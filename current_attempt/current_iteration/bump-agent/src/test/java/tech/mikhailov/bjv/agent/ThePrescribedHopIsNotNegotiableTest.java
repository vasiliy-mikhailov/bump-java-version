package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hop is the experiment's independent variable. An agent that picks it makes every run a
 * different experiment, and it went wrong in exactly that way: the surveyor demoted three repos
 * from 11-&gt;17 to 8-&gt;11 off a `release 8` flag, the chain baselined at a JDK those projects
 * cannot build on, and all three were recorded as the project's failure.
 */
class ThePrescribedHopIsNotNegotiableTest {

    private static String[] parse(String bump) throws Exception {
        var ctor = Bump.class.getDeclaredConstructor(java.nio.file.Path.class, String.class,
                Trace.class);
        ctor.setAccessible(true);
        Object b = ctor.newInstance(java.nio.file.Path.of("."), bump, null);
        var from = Bump.class.getDeclaredField("from");
        var to = Bump.class.getDeclaredField("to");
        from.setAccessible(true);
        to.setAccessible(true);
        return new String[]{(String) from.get(b), (String) to.get(b)};
    }

    @Test
    void theHopComesFromTheManifestRow() throws Exception {
        assertEquals("11", parse("owner/repo|abc123|11|17")[0]);
        assertEquals("17", parse("owner/repo|abc123|11|17")[1]);
    }

    @Test
    void aHopMaySpanMoreThanOneLtsStep() throws Exception {
        // Nothing says a hop is one rung. 11->25 is a legitimate row and must arrive intact.
        String[] hop = parse("owner/repo|abc123|11|25");
        assertEquals("11", hop[0]);
        assertEquals("25", hop[1]);
    }

    @Test
    void aRowWithoutAHopIsAManifestBugNotSomethingToGuess() {
        Exception e = assertThrows(Exception.class, () -> parse("owner/repo|abc123"));
        Throwable cause = e.getCause() == null ? e : e.getCause();
        assertTrue(String.valueOf(cause.getMessage()).contains("repo|sha|from|to"),
                "the error must name the shape it wanted, got: " + cause.getMessage());
    }

    @Test
    void aMultiStepHopStillGetsTheRecipesOfEveryRungItCrosses() {
        // Java8toJava11 is the only recipe that handles what JEP 320 removed, so an 8->17 hop needs
        // it as much as 8->11 does: nothing stopped at 11, but the wall is still there.
        //
        // ASSERTED AGAINST THE PROMPT, because that is where the decision lives now. It used to be
        // in Migrate.program(), a deterministic recipe builder that had lost its last caller — the
        // agents assemble their own recipe file, so a rule the harness computed and never handed to
        // anyone was doing nothing but passing this test.
        String direct = bumperPrompt(new Hop(8, 11));
        String spanning = bumperPrompt(new Hop(8, 17));
        String above = bumperPrompt(new Hop(17, 21));

        assertTrue(direct.contains("Java8toJava11"), direct);
        assertTrue(spanning.contains("Java8toJava11"),
                "a hop that crosses 11 needs 11's recipes even though it does not stop there");
        assertTrue(spanning.contains("UpgradeBuildToJava17"));
        assertFalse(above.contains("Java8toJava11"),
                "and a hop that never reaches 11 is not told about it: a rule that cannot fire is "
                        + "an invitation to apply it anyway");
    }

    /** What the agent that moves the target is actually handed, for one hop. */
    private static String bumperPrompt(Hop hop) {
        return Agents.forHop(hop, java.nio.file.Path.of("/tmp")).stream()
                .filter(d -> d.name().equals("bump-doer"))
                .findFirst().orElseThrow()
                .systemPrompt();
    }
}
