package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second machine must not inherit the author's home directory as a compiled-in mount.
 */
class TheHarnessEnvIsNotTheAuthorsMachineTest {

    @Test
    void runnerEnvDoesNotPinTheAuthorsCaches(@TempDir Path ws) {
        Map<String, String> env = Runner.env(ws);
        assertEquals(ws.toString(), env.get("BJV_WS"));
        assertEquals("mvn-cache", env.get("BJV_NET"));
        assertFalse(env.values().stream().anyMatch(v -> v.contains("vmihaylov")),
                "a path that only exists on the author's host: " + env);
        // Unset caches are omitted, not invented: jvm-run skips a missing mount.
        if (System.getenv("BJV_M2") == null || System.getenv("BJV_M2").isBlank()) {
            assertFalse(env.containsKey("BJV_M2"));
        }
        if (System.getenv("BJV_SETTINGS") == null || System.getenv("BJV_SETTINGS").isBlank()) {
            assertFalse(env.containsKey("BJV_SETTINGS"));
        }
    }

    @Test
    void blankEnvIsTreatedAsUnset() {
        assertTrue(Env.get("BJV_THIS_VARIABLE_DOES_NOT_EXIST") == null);
        assertEquals("fallback", Env.get("BJV_THIS_VARIABLE_DOES_NOT_EXIST", "fallback"));
        assertTrue(Env.flag("BJV_THIS_VARIABLE_DOES_NOT_EXIST", true));
        assertFalse(Env.flag("BJV_THIS_VARIABLE_DOES_NOT_EXIST", false));
    }
}
