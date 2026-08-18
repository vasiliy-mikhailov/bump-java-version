package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The facts that decide a Boot 2 to 3 migration live inside jars, where no workspace tool reaches.
 *
 * <p>Every assertion here is one the failed 0xiaoyu/XiaoYu run needed and could not get: that
 * {@code Kaptcha} is an interface rather than a class, that the starter registers only through
 * {@code spring.factories}, and that exactly one of its classes is the javax blocker.
 */
class TheJarReaderAnswersWhatTheWorkspaceCannotTest {

    @Test
    void anInterfaceIsReportedAsOneSoNobodyWritesNewAgainstIt() throws Exception {
        Jars jars = repo(Files.createTempDirectory("m2"));
        String out = jars.describeType("org.example", "lib", "1.0.0", "java.lang.Runnable");
        assertTrue(out.startsWith("interface java.lang.Runnable"), out);
        assertTrue(out.contains("THIS IS AN INTERFACE"), "the mistake it exists to prevent");
        assertTrue(out.contains("void run()"), "members with readable signatures: " + out);
    }

    @Test
    void aClassIsNotMistakenForOne() throws Exception {
        Jars jars = repo(Files.createTempDirectory("m2"));
        String out = jars.describeType("org.example", "lib", "1.0.0", "java.lang.StringBuilder");
        assertTrue(out.startsWith("class java.lang.StringBuilder"), out);
        assertTrue(!out.contains("THIS IS AN INTERFACE"));
    }

    @Test
    void springFactoriesIsCalledOutAsBootTwoOnly() throws Exception {
        Jars jars = repo(Files.createTempDirectory("m2"));
        String out = jars.describe("org.example", "lib", "1.0.0");
        assertTrue(out.contains("SPRING BOOT 3 DOES NOT READ IT"),
                "the whole reason a Boot 2 starter contributes no beans: " + out);
        assertTrue(out.contains("com.example.LibAutoConfiguration"), "the entry itself: " + out);
        assertTrue(out.contains("AutoConfiguration.imports (the Boot 3 mechanism): ABSENT"), out);
    }

    @Test
    void missingCoordinatesAnswerRatherThanThrowAtTheModel() throws Exception {
        Jars jars = repo(Files.createTempDirectory("m2"));
        Jars.NotFound e = assertThrows(Jars.NotFound.class,
                () -> jars.describe("org.example", "no-such-artifact", null));
        // The executor turns this into a readable answer; a stack trace would end the agent's turn.
        assertTrue(e.getMessage().contains("no-such-artifact"), e.getMessage());
    }

    /** A local repository holding one jar: two JDK classes plus a Boot 2 style registration file. */
    private static Jars repo(Path m2) throws Exception {
        Path dir = m2.resolve("repository/org/example/lib/1.0.0");
        Files.createDirectories(dir);
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(dir.resolve("lib-1.0.0.jar")))) {
            for (Class<?> c : new Class<?>[] {Runnable.class, StringBuilder.class}) {
                String path = c.getName().replace('.', '/') + ".class";
                jar.putNextEntry(new JarEntry(path));
                jar.write(bytes(path));
                jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("META-INF/spring.factories"));
            jar.write(("org.springframework.boot.autoconfigure.EnableAutoConfiguration="
                    + "com.example.LibAutoConfiguration\n").getBytes());
            jar.closeEntry();
        }
        return new Jars(m2.resolve("repository"));
    }

    private static byte[] bytes(String resource) throws Exception {
        try (var in = ClassLoader.getSystemResourceAsStream(resource)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
