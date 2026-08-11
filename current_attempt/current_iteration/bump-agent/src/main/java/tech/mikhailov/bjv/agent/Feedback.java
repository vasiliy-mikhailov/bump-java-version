package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A JUDGEMENT ABOUT ONE ANSWER — the label that makes the trace training data rather than a log.
 *
 * <p>{@code trace.jsonl} already holds every (prompt, reply) pair in full. What it cannot hold is
 * whether the reply was any good, because nothing in the run knows: the gate settles whether the
 * bump went green, not whether the fixer's edit was the one a reviewer wanted. That judgement comes
 * from a person, through the dashboard, and this is where it lands.
 *
 * <p>IT POINTS AT ONE EVENT, NOT AT A BUMP. Prompt tuning optimises ONE agent's prompt, so a
 * complaint filed against a whole bump cannot be attributed. {@link #event} is the index of the
 * {@code asked} entry within that bump's trace, stable because events are only appended.
 *
 * <p>AND IT CARRIES THE PROMPT AND THE REPLY, not a reference to them: a row here is a complete
 * training example, so a tuning run needs this file and nothing else. Duplication is the cheap half
 * of that trade.
 */
record Feedback(String bump, String agent, int event, String note, String at,
                String prompt, String reply) {

    /** Append. The file is the corpus; there is no database in this program. */
    void appendTo(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        String row = "{\"bump\":\"" + Settlement.escape(bump)
                + "\",\"agent\":\"" + Settlement.escape(agent)
                + "\",\"event\":" + event
                + ",\"note\":\"" + Settlement.escape(note)
                + "\",\"at\":\"" + Settlement.escape(at)
                + "\",\"prompt\":\"" + Settlement.escape(prompt)
                + "\",\"reply\":\"" + Settlement.escape(reply) + "\"}\n";
        Files.writeString(file, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
