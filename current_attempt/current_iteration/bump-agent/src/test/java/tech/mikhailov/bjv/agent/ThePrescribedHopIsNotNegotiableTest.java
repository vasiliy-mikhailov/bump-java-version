package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void aMultiStepHopStillGetsTheRecipesOfEveryRungItCrosses(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path ws) throws Exception {
        // Java8toJava11 is the only recipe that handles what JEP 320 removed, so an 8->17 hop
        // needs it as much as 8->11 does: nothing stopped at 11, but the wall is still there.
        var m = Migrate.class.getDeclaredMethod("program", int.class, int.class);
        m.setAccessible(true);
        // program() reads the project to pick the Spring line, so it needs a real (empty) tree.
        Migrate migrate = new Migrate(ws, "/nonexistent", null);
        @SuppressWarnings("unchecked")
        var direct = (java.util.List<String>) m.invoke(migrate, 8, 11);
        @SuppressWarnings("unchecked")
        var spanning = (java.util.List<String>) m.invoke(migrate, 8, 17);
        assertTrue(direct.contains("org.openrewrite.java.migrate.Java8toJava11"));
        assertTrue(spanning.contains("org.openrewrite.java.migrate.Java8toJava11"),
                "a hop that crosses 11 needs 11's recipes even though it does not stop there");
        assertTrue(spanning.contains("org.openrewrite.java.migrate.UpgradeBuildToJava17"));
    }
}
