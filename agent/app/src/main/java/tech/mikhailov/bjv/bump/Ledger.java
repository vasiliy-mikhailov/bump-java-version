package tech.mikhailov.bjv.bump;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.ratchet.record.Trace;
import tech.mikhailov.bjv.jvm.Tree;

/**
 * WHAT THIS RUN HAS ALREADY DONE, AND HOW TO PUT IT BACK.
 *
 * <p>Two records, and they answer different questions. The event log holds what was TRIED,
 * including the attempts a critic rejected and the reason it gave; git holds what LANDED. An agent
 * about to repeat a step somebody else already abandoned is reading neither of them.
 *
 * <p>The rewind pair is the writing half of the same idea and is bounded at the campaign's own
 * floor. A troubleshooting campaign may abandon its own steps; the migration underneath them is
 * not its to undo.
 */
final class Ledger {

    private Ledger() {
    }

    /**
     * What has already been done to this workspace, and what each of those steps changed.
     *
     * <p>Given to everyone. Each stage now commits as it lands, which means a critic is handed only
     * its own producer's diff -- right for judging that producer, and a loss of every bit of context
     * about what came before. Before the commits that context arrived whether it was wanted or not,
     * as one ever-growing diff. This is the same information, asked for rather than dumped.
     */
    static Map<ToolSpecification, ToolExecutor> history(Tree tree, Trace trace) {
        Map<ToolSpecification, ToolExecutor> two = new LinkedHashMap<>();

        two.put(ToolSpecification.builder()
                .name("what_happened")
                .description("This bump's own event log: every stage, every answer, every objection "
                        + "and every tool result so far, one line each, oldest first. Git shows what "
                        + "LANDED; this shows what was TRIED, including the attempts that were "
                        + "rejected and why. Read it before repeating something. Narrow it with "
                        // FOUR OF THE SEVEN NAMED HERE DID NOT EXIST. migrate, prepare and
                        // troubleshooter were deleted stages, survey never writes an `applied`
                        // row, and happened() returns nothing for a stage it does not match, so
                        // every agent was offered a filter vocabulary that silently found nothing.
                        + "`stage` (baseline, before-pins-doer, bump, after-pins-doer, modules, "
                        + "gate, module-repair-step-doer, security-before, security-after) "
                        + "or `agent` when the whole log is too much.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("stage", "optional stage to filter to")
                        .addStringProperty("agent", "optional agent to filter to")
                        .addIntegerProperty("limit", "how many of the most recent events, default 80")
                        .build())
                .build(), (request, memoryId) -> {
                    String stage = Json.read(request.arguments(), "stage");
                    String agent = Json.read(request.arguments(), "agent");
                    int limit = Json.number(request.arguments(), "limit", 60);
                    String log = trace.happened(stage, agent, limit);
                    return log.isBlank() ? "nothing recorded yet for that filter" : log;
                });

        two.put(ToolSpecification.builder()
                .name("history")
                .description("What has already been done to this workspace, oldest first, as "
                        + "<sha>  <what it was>. Stages of this migration appear as `bjv: ...`; "
                        + "anything below them is the project's own history. Use it to find out "
                        + "what an earlier stage did before assuming it did nothing.")
                .parameters(JsonObjectSchema.builder().build())
                .build(), (request, memoryId) -> {
                    String log = tree.log();
                    return log.isBlank() ? "no history could be read" : log;
                });

        two.put(ToolSpecification.builder()
                .name("changed_in")
                .description("The edits one entry from `history` actually made. A label says what a "
                        + "step was called; this says what it did.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("sha", "a commit from history")
                        .required("sha")
                        .build())
                .build(), (request, memoryId) -> {
                    String asked = Json.read(request.arguments(), "sha").strip();
                    String sha = tree.resolve(asked);
                    if (sha.isBlank()) {
                        return "no commit called " + asked + ". Use history for the list.";
                    }
                    String shown = tree.show(sha);
                    return shown.isBlank() ? "nothing readable at " + asked : shown;
                });
        return two;
    }

    /**
     * Moving the workspace back between landed steps, for the outer troubleshoot pair.
     *
     * <p>A campaign is a sequence of steps, so the pair running it needs to see that sequence and to
     * be able to abandon a line that went nowhere. Giving them the rewind as a TOOL rather than as a
     * branch in the harness means the decision to go back is theirs and is argued for in the trace,
     * which is the same reason every other judgement here belongs to an agent.
     *
     * <p>Bounded at {@code floor}, and the bound is the point: a rewind past the campaign's first
     * commit deletes the deterministic migration underneath it. That is precisely the failure that
     * an unscoped revert caused for five bumps, and it must not come back as a tool call.
     */
    static Map<ToolSpecification, ToolExecutor> rewind(Tree tree, String floor) {
        Map<ToolSpecification, ToolExecutor> two = new LinkedHashMap<>();

        two.put(ToolSpecification.builder()
                .name("steps_so_far")
                .description("The steps this troubleshooting campaign has landed, oldest first, as "
                        + "<sha>  <what it was>. Use it to see what has already been tried before "
                        + "deciding what to do next, and to name a commit for rewind_to.")
                .parameters(JsonObjectSchema.builder().build())
                .build(), (request, memoryId) -> {
                    String log = tree.history(floor);
                    return log.isBlank()
                            ? "no steps have landed yet in this campaign" : log;
                });

        two.put(ToolSpecification.builder()
                .name("rewind_to")
                .description("Put the workspace back to a landed step, discarding everything after "
                        + "it. Use it to abandon a line of edits that led nowhere, so the next "
                        + "attempt starts from a known state instead of on top of the wreckage. "
                        + "Only commits from steps_so_far are reachable; the migration underneath "
                        + "this campaign cannot be undone.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("sha", "a commit from steps_so_far")
                        .required("sha")
                        .build())
                .build(), (request, memoryId) -> {
                    String asked = Json.read(request.arguments(), "sha").strip();
                    String sha = tree.resolve(asked);
                    if (sha.isBlank()) {
                        return "no commit called " + asked + ". Use steps_so_far for the list.";
                    }
                    if (!tree.isAtOrAfter(sha, floor)) {
                        return "REFUSED: " + asked + " is older than this campaign. Rewinding there "
                                + "would delete the migration this troubleshooting sits on top of, "
                                + "which is not yours to undo. The earliest you may go back to is "
                                + "the first entry in steps_so_far.";
                    }
                    tree.revertTo(sha);
                    return "workspace is back at " + asked + ". Everything after it is gone.\n"
                            + tree.history(floor);
                });
        return two;
    }
}
