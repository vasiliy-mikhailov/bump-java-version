package tech.mikhailov.bjv.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The prompt says never touch a test; this class is where that is a rule and not a suggestion. */
class TestCodeIsNotThePatchTest {

    @Test
    void anEditUnderSrcTestIsRejectedBeforeAnythingReachesDisk(@TempDir Path ws) throws Exception {
        Path test = ws.resolve("src/test/java/T.java");
        Files.createDirectories(test.getParent());
        Files.writeString(test, "old");
        Edits.Applied a = Edits.apply(ws, "EDIT src/test/java/T.java\n<<<<\nold\n====\nnew\n>>>>");
        assertEquals(0, a.count());
        assertEquals("old", Files.readString(test));
        assertTrue(a.report().contains("test code"));
    }

    @Test
    void anAmbiguousAnchorAppliesNothingAtAll(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"), "<x>1</x>\n<x>1</x>");
        Edits.Applied a = Edits.apply(ws, "EDIT pom.xml\n<<<<\n<x>1</x>\n====\n<x>2</x>\n>>>>");
        assertEquals(0, a.count());
        assertTrue(a.report().contains("not unique"));
    }

    @Test
    void aGoodEditLandsExactly(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("pom.xml"), "<v>11</v>");
        Edits.Applied a = Edits.apply(ws,
                "EDIT pom.xml\n<<<<\n<v>11</v>\n====\n<v>17</v>\n>>>>\nWHY: target");
        assertEquals(1, a.count());
        assertEquals("<v>17</v>", Files.readString(ws.resolve("pom.xml")));
    }
}
