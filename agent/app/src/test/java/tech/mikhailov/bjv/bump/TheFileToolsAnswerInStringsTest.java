package tech.mikhailov.bjv.bump;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT list_dir, read_file AND edit_file SAY, WORD FOR WORD.
 *
 * <p>These three arrived from a jar until they were written here, and the strings they answer with
 * are not this program's private business: they are read by sixty-five agents that have been tuned
 * against them, and they are in every trace in the corpus. So the texts are pinned. A failure here
 * is not a broken tool, it is a changed prompt.
 *
 * <p>NOTHING THROWS. A tool executor that lets an exception escape does not degrade into something
 * the model can read, it ends the agent's turn, so every failure below is a sentence rather than a
 * stack trace. That is the property most worth keeping and the easiest to lose.
 */
class TheFileToolsAnswerInStringsTest {

    @Test
    void listDirNamesOneLevelAndMarksTheDirectories(@TempDir Path ws) throws Exception {
        Files.createDirectories(ws.resolve("core/src"));
        Files.writeString(ws.resolve("pom.xml"), "<project/>");

        assertEquals("core/\npom.xml", call(ws, "list_dir", "{\"path\":\".\"}"));
        // A model that opened with an empty object meant the workspace root.
        assertEquals("core/\npom.xml", call(ws, "list_dir", "{}"));
        assertEquals("Not a directory: pom.xml", call(ws, "list_dir", "{\"path\":\"pom.xml\"}"));
        assertEquals("", call(ws, "list_dir", "{\"path\":\"core/src\"}"),
                "an empty directory answers with nothing at all");
    }

    @Test
    void readFileReturnsTheFileAndOtherwiseSaysWhyNot(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"), "<project/>\n");

        assertEquals("<project/>\n", call(ws, "read_file", "{\"path\":\"pom.xml\"}"));
        assertEquals("<project/>\n", call(ws, "read_file",
                "{\"path\":\"" + ws.toAbsolutePath().normalize().resolve("pom.xml") + "\"}"),
                "an absolute path inside the workspace is still inside the workspace");
        assertEquals("Not a file: nope.xml", call(ws, "read_file", "{\"path\":\"nope.xml\"}"));
        assertEquals("Error: Path escapes workspace: ../secrets",
                call(ws, "read_file", "{\"path\":\"../secrets\"}"));
    }

    @Test
    void readFileHandsBackTheKeyAModelShouldHaveUsed(@TempDir Path ws) throws Exception {
        // MODELS REACH FOR file_path, because half the tools they have seen elsewhere use it. A
        // read that silently returned nothing cost a round for no reason; this costs the same round
        // and spends it saying which key works.
        Files.writeString(ws.resolve("pom.xml"), "<project/>");

        assertEquals("Error: this tool's JSON schema uses the key \"path\", not \"file_path\". "
                        + "Retry with {\"path\": \"...\"}.",
                call(ws, "read_file", "{\"file_path\":\"pom.xml\"}"));
    }

    @Test
    void anEditIsExactAndUniqueOrItDoesNotHappen(@TempDir Path ws) throws Exception {
        Path pom = ws.resolve("pom.xml");
        Files.writeString(pom, "<release>17</release>\n<release>17</release>\n");

        assertEquals("Error: old_string is not unique; provide more context or use "
                        + "replace_all=true.",
                call(ws, "edit_file", "{\"path\":\"pom.xml\",\"old_string\":\"17\","
                        + "\"new_string\":\"21\"}"));
        assertTrue(Files.readString(pom).contains("<release>17</release>\n<release>17</release>"),
                "a refused edit changes nothing");

        assertEquals("Error: old_string not found in file (must match exactly, including "
                        + "whitespace).",
                call(ws, "edit_file", "{\"path\":\"pom.xml\",\"old_string\":\"<release> 17\","
                        + "\"new_string\":\"x\"}"));

        assertEquals("Updated " + ws.toAbsolutePath().normalize().resolve("pom.xml"),
                call(ws, "edit_file", "{\"path\":\"pom.xml\",\"old_string\":\"17\","
                        + "\"new_string\":\"21\",\"replace_all\":true}"));
        assertEquals("<release>21</release>\n<release>21</release>\n", Files.readString(pom));
    }

    @Test
    void anEditIsVisibleToTheVeryNextRead(@TempDir Path ws) throws Exception {
        // THE READ CACHE MUST NOT OUTLIVE THE EDIT. A doer reads a pom, edits it and reads it back
        // to check, all inside one turn; a stale answer there would have it correcting a change it
        // had already made.
        Map<ToolSpecification, ToolExecutor> tools = Workspace.tools(ws);
        Files.writeString(ws.resolve("pom.xml"), "<release>17</release>\n");

        assertEquals("<release>17</release>\n", run(tools, "read_file", "{\"path\":\"pom.xml\"}"));
        run(tools, "edit_file", "{\"path\":\"pom.xml\",\"old_string\":\"17\","
                + "\"new_string\":\"21\"}");
        assertEquals("<release>21</release>\n", run(tools, "read_file", "{\"path\":\"pom.xml\"}"));
    }

    @Test
    void nothingHereCreatesAFile(@TempDir Path ws) {
        // A new file is not a migration step, and a tool nobody may call is a tool that will
        // eventually be handed to somebody by accident.
        assertEquals(3, Workspace.tools(ws).size());
        assertFalse(Workspace.tools(ws).keySet().stream()
                        .anyMatch(spec -> spec.name().equals("write_file")),
                "the set that arrived from outside had a fourth tool; no phase was ever given it");
    }

    @Test
    void malformedArgumentsAreAnAnswerRatherThanAFailure(@TempDir Path ws) {
        assertTrue(call(ws, "read_file", "not json at all").startsWith("Error: "),
                "an executor that throws ends the whole turn, so this one does not");
    }

    private static String call(Path ws, String tool, String arguments) {
        return run(Workspace.tools(ws), tool, arguments);
    }

    private static String run(Map<ToolSpecification, ToolExecutor> tools, String tool,
                              String arguments) {
        return tools.entrySet().stream()
                .filter(entry -> entry.getKey().name().equals(tool))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + tool))
                .execute(ToolExecutionRequest.builder().name(tool).arguments(arguments).build(),
                        null);
    }
}
