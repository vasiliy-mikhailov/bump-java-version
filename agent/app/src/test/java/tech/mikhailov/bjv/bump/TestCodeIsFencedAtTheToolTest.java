package tech.mikhailov.bjv.bump;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tech.mikhailov.bjv.engine.Trace;
import tech.mikhailov.bjv.jvm.Tree;

/**
 * The prompts say "never edit a test". This is where that is a rule instead of a suggestion: the
 * guard sits at the executor, so it holds whatever the model reasoned its way to.
 */
class TestCodeIsFencedAtTheToolTest {

    @Test
    void anEditUnderATestRootIsRefusedAndTheFileIsUntouched(@TempDir Path ws) throws Exception {
        Path test = ws.resolve("src/test/java/T.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "original");

        ToolExecutor edit = executor(ws, "edit_file");
        String answer = edit.execute(ToolExecutionRequest.builder()
                .name("edit_file")
                .arguments("{\"path\":\"src/test/java/T.java\",\"old_string\":\"original\","
                        + "\"new_string\":\"rewritten\"}")
                .build(), null);

        assertTrue(answer.startsWith("REFUSED:"), answer);
        assertEquals("original", Files.readString(test));
    }

    @Test
    void aProducerNeverGetsWriteFile(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        Map<ToolSpecification, ToolExecutor> tools =
                Tools.patching(ws, null, tree(ws), "17", new Silent(), "bump-doer");
        assertFalse(tools.keySet().stream().anyMatch(s -> s.name().equals("write_file")),
                "a new file is not a migration step");
        assertTrue(tools.keySet().stream().anyMatch(s -> s.name().equals("edit_file")));
    }

    @Test
    void aCriticCannotEditAtAll(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        Map<ToolSpecification, ToolExecutor> tools = Tools.reading(ws, tree(ws), new Silent(), "bump-verifier");
        assertFalse(tools.keySet().stream().anyMatch(s -> s.name().startsWith("edit")
                        || s.name().startsWith("write")),
                "a certification must not manufacture the evidence it certifies");
    }

    private static ToolExecutor executor(Path ws, String name) throws Exception {
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        return Tools.patching(ws, null, tree(ws), "17", new Silent(), "step-doer").entrySet().stream()
                .filter(e -> e.getKey().name().equals(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + name));
    }

    /** A trace that records nothing: this test is about the guard, not about the record. */
    private static final class Silent implements Trace {
        public void asked(String a, String p, String r) {
        }

        public void applied(String s, String w) {
        }

        public void tool(String a, String t, String args, String result) {
        }

        public void thought(String finish, String thinking, String content) {
        }

        public void built(String phase, Trace.Outcome r) {
        }

        public void settled(String b, String s, String w, boolean x, boolean y) {
        }

        public void failed(String b, Throwable c) {
        }

        public void progress(String b, String n) {
        }

        public void priced(String b, String m, String i) {
        }
    }

    /** A tree over a workspace that is not a git repo: the history tools answer, they do not throw. */
    private static Tree tree(Path ws) {
        return new Tree(ws, note -> { });
    }
}
