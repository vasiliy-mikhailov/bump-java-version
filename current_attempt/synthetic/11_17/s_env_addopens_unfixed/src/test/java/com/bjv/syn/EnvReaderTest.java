package com.bjv.syn;

import org.junit.jupiter.api.Test;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvReaderTest {

    @Test
    @SetEnvironmentVariable(key = "BJV_SYN_FLAG", value = "on")
    void readsInjectedEnv() {
        assertEquals("on", new EnvReader().read("BJV_SYN_FLAG"));
    }

    @Test
    @SetEnvironmentVariable(key = "BJV_SYN_MODE", value = "fast")
    void readsSecondInjectedEnv() {
        assertEquals("fast", new EnvReader().read("BJV_SYN_MODE"));
    }
}
