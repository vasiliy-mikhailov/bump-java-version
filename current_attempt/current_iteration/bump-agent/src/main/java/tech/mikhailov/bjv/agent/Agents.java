package tech.mikhailov.bjv.agent;

/**
 * THE FOUR, EACH WITH ITS OWN CLOSED SET OF ANSWERS.
 *
 * <p>There is no orchestrator: an agent asked to follow an order it can rewrite will rewrite it.
 * {@link Bump} runs the order; these are the four things it calls.
 *
 * <p>ONE WRITES AND THREE JUDGE. The fixer's output is checked by the compiler and the gate, so it
 * proposes edits, in a format {@link Edits} applies mechanically, and it may never touch a test:
 * the harness enforces that, not the prompt, because a rule the model can rewrite is a suggestion.
 * The judges' answers are BRANCHED ON, so they get the record and a word list, and nothing else.
 *
 * <p>Prompts live here, in the code, because the order and the prompts are one design: a prompt
 * edited apart from the loop that consumes its answer drifts into asking for things nothing reads.
 * The trace records every (prompt, reply) in full, and {@code feedback/} holds the human judgement
 * that turns those pairs into training data for improving what is written here.
 */
final class Agents {

    @FunctionalInterface
    interface Agent {
        String run(String task);
    }

    private final Llm llm;
    private final Trace trace;

    Agents(Llm llm, Trace trace) {
        this.llm = llm;
        this.trace = trace;
    }

    /** Proposes the smallest edit that clears the wall the log shows. Never a test file. */
    Agent fixer() {
        return agent("fixer", """
                You are migrating a Java project one LTS step. The deterministic pipeline has already \
                run the OpenRewrite recipes, the version floors and the known-wall table; what you are \
                given is the wall none of that recognised. Propose the SMALLEST edit that clears it.

                Rules the harness enforces, so do not argue with them: you may not edit anything under \
                src/test (a lost test scores zero regardless of anything else this bump achieves), and \
                your edit is applied verbatim by a machine.

                Answer with one or more edit blocks, nothing between them:

                EDIT <path relative to the repo root>
                <<<<
                <exact text currently in the file, enough lines to be unique>
                ====
                <the replacement text>
                >>>>

                After the last block, one line: WHY: <what the wall is and why this clears it>.

                If the wall cannot be cleared by editing the build or main sources, answer exactly \
                BLOCKED: <why>. That is a useful answer; a speculative edit is not.
                """);
    }

    /** Judges the fixer's edit: migration fix, or gaming the gate? Read-only, word list. */
    Agent fixCritic() {
        return agent("fix-critic", """
                A colleague proposed an edit to get a Java version migration past its build gate. \
                Judge ONE question: is this a migration fix, or does it game the gate?

                Gaming looks like: weakening or skipping tests by configuration (excludes, skipTests, \
                surefire excludes), deleting functionality instead of migrating it, pinning a tool to \
                a version that ignores the new bytecode rather than reading it, or editing generated \
                output instead of its source.

                Answer `sound` when the edit is a real migration step a maintainer would keep. Answer \
                `gaming` and name the exact line when it is not. Answer `off-target` when the edit is \
                honest but aims at the wrong wall: say which wall the log actually shows.
                """);
    }

    /**
     * Argues the cases execution could not settle. Asked ONLY when the gate established nothing:
     * where the builds established the facts, {@link Bump} computes the settlement and no model is
     * called, because routing a deterministic outcome through a model turns it into a sampled one.
     */
    Agent verdict() {
        return agent("verdict", """
                A Java version bump ended without the gate establishing a verdict. You argue what \
                this bump IS, from the record you are given.

                `blocked-dependency`  — a dependency has no version compatible with the target JDK. \
                Name it and say what was tried.
                `behavior-change`     — the target JDK changed observable behaviour (locale data, \
                regex semantics, added methods that shadow) and only a test edit could reconcile it, \
                which the rules forbid. Name the exact change.
                `infra`               — the environment failed the bump: resolution, timeouts, disk. \
                A tooling failure must not read as a migration failure.

                One word first, then the argument. These mean different things to whoever reads this \
                next, so choose the word for the reader, not for the ego of the run.
                """);
    }

    /** Prices the bump from the record: what the same migration would have cost a developer. */
    Agent estimator() {
        return agent("estimator", """
                You read a completed attempt to bump a Java project one LTS step, and estimate what \
                the same work would have cost a competent Java developer who had not seen this code \
                before.

                Charge the work that was actually done, not the outcome: reading the failing build, \
                each wall recognised and cleared, each edit proposed and reviewed, and the dead ends. \
                A wall the table cleared in one turn still cost a human the diagnosis. Charge the \
                dead ends too; a human would have paid for those attempts.

                Answer with ONE line first: `minutes: N`. Then three to six lines itemising what you \
                charged, saying which part dominated.
                """);
    }

    private Agent agent(String name, String prompt) {
        return task -> {
            String reply;
            try {
                reply = llm.ask(prompt, task);
            } catch (RuntimeException e) {
                // An unreachable judge withholds; an unreachable writer has nothing to apply. Either
                // way the empty answer is the record, and the chain's branching already reads it.
                reply = "";
                trace.progress("", name + " unreachable: " + e.getMessage());
            }
            reply = reply == null ? "" : reply;
            trace.asked(name, prompt + "\n\n---\n\n" + task, reply);
            return reply;
        };
    }
}
