package tech.mikhailov.bjv.engine;

import java.io.IOException;

/**
 * PLANNER, DOER, VERIFIER, AND THE VERIFIER HOLDS THE LOOP.
 *
 * <p>Every stage was a producer and a critic, with the loop written into the producer's side: the
 * producer ran repeatedly and the critic was asked once, at the end. That put the two jobs in the
 * wrong hands. Deciding what to do and deciding whether it worked are different questions, and the
 * agent that chose a plan is the worst available judge of whether to keep running it.
 *
 * <p>It also hid a real defect for as long as it existed. The pin phase looped its producer and
 * called check_pins between rounds, but check_pins concatenated every build file in the tree and
 * returned the first version it matched anywhere, so it answered about whichever module the
 * filesystem happened to walk first. Sixteen of the twenty-seven multi-module repositories in this
 * corpus carry some package at more than one version. The loop terminated reporting every pin met,
 * the critic saw only that end state and agreed, and nothing in the construction could have caught
 * it. A verifier that runs every round, against one module at a time, cannot make that mistake.
 *
 * <p>So the doer executes one plan once and has no opinion about repetition. The verifier reads what
 * the workspace says afterwards and returns one of three words, which is the whole control flow:
 *
 * <ul>
 *   <li>{@code done} closes the stage.
 *   <li>{@code again} keeps the plan and re-runs the doer, which is told what the objection was.
 *   <li>{@code replan} throws the plan away and returns to the planner.
 * </ul>
 *
 * <p>The two failure paths are separate on purpose. Collapsing them is how a loop spends its whole
 * budget re-running a plan that was wrong from the first round, which is the shape of the sixteen
 * gate turns this corpus has spent watching a troubleshooter re-apply an edit its reviewer had
 * already rejected.
 *
 * <p>Nesting is the same object: a doer may itself be a triad, and each level loops on its own
 * verifier. The inner one closes first, so an outer verifier judges a finished piece of work rather
 * than a half-run loop.
 */
public final class Triad extends Flow.Node {

    /** One execution of one plan. Not necessarily an agent: the enumerator's doer is Maven. */
    @FunctionalInterface
    public interface Doer {
        /**
         * @param plan     what the planner settled on, unchanged across {@code again} rounds
         * @param feedback the verifier's objection, empty on the first round
         */
        String run(String plan, String feedback) throws IOException;
    }

    /**
     * What the workspace says once the doer has run.
     *
     * <p>Every verifier is handed this rather than left to ask. The corpus has a preparer answering
     * NOTHING-TO-DO while its own stage recorded edits, and a troubleshooter reporting a fix it had
     * reverted a turn earlier; a report is an opinion and the build files are not.
     */
    @FunctionalInterface
    public interface Facts {
        String read() throws IOException;
    }

    private final Agent planner;
    private final Doer doer;
    private final Agent verifier;
    private final Facts facts;
    private final Trace trace;
    private final String bump;
    private final int rounds;

    public Triad(String stage, Agent planner, Doer doer, Agent verifier, Facts facts,
          Trace trace, String bump, int rounds) {
        super(stage);
        this.planner = planner;
        this.doer = doer;
        this.verifier = verifier;
        this.facts = facts;
        this.trace = trace;
        this.bump = bump;
        this.rounds = Math.max(1, rounds);
    }

    /**
     * WHAT IT CONTAINS, so a triad can be drawn without anyone writing the drawing down twice.
     *
     * <p>The planner and the verifier are leaves here rather than named children: naming them would
     * put three lines on a picture where the interesting fact is one, that this stage plans, does
     * and verifies like every other. What is worth showing is what the DOER contains, which is
     * where a sub-chain lives.
     */
    @Override
    public java.util.List<Agent> inside() {
        return doer instanceof Agent nested ? java.util.List.of(nested) : java.util.List.of();
    }

    /** What the stage ended up having done, which is the doer's last word. */
    @Override
    public String run(String brief) throws IOException {
        String plan = planner.run(brief);
        String feedback = "";
        String did = "";
        for (int round = 1; round <= rounds; round++) {
            did = doer.run(plan, feedback);
            String state = facts.read();
            String judgement = verifier.run(brief
                    + "\n\nThe plan this stage is working to:\n" + plan
                    + "\n\nWhat your colleague reports doing:\n" + did
                    + "\n\nWhat the workspace says now:\n" + state);
            String verdict = verdictOf(judgement);
            if (verdict.equals("done")) {
                trace.progress(bump, name() + ": settled after " + round
                        + (round == 1 ? " round" : " rounds"));
                return did;
            }
            if (round == rounds) {
                // The budget is spent, and saying so is more useful than a verdict nobody reached.
                trace.progress(bump, name() + ": " + rounds + " rounds spent, last word was "
                        + verdict + " — " + because(judgement));
                return did;
            }
            trace.progress(bump, name() + ": " + verdict + " — " + because(judgement));
            if (verdict.equals("replan")) {
                plan = planner.run(brief
                        + "\n\nYour previous plan:\n" + plan
                        + "\n\nIt was carried out, and a reviewer sent the whole plan back:\n"
                        + judgement
                        + "\n\nWhat the workspace says now:\n" + state
                        + "\n\nPlan again. The objection is to the plan, not to the execution, so a"
                        + " plan that differs only in wording will come straight back.");
                feedback = "";
            } else {
                feedback = "\n\nYou did this once already and a reviewer objected:\n" + judgement
                        + "\n\nWhat the workspace says now:\n" + state
                        + "\n\nThe plan stands. Address the objection.";
            }
        }
        return did;
    }

    /**
     * The verifier's word, with a blank reply read as {@code again} rather than as agreement.
     *
     * <p>{@code Bump.word} falls back to its first argument, and an empty reply is a live failure
     * mode on this endpoint rather than a hypothetical. Defaulting silence to {@code done} would
     * close a stage because a request came back empty, which is the one reading of silence that
     * loses work.
     */
    private static String verdictOf(String judgement) {
        if (judgement == null || judgement.isBlank()) {
            return "again";
        }
        return Reply.word(judgement, "done", "again", "replan");
    }

    /**
     * THE FIRST LINE THAT SAYS SOMETHING, which is not the same as the first line.
     *
     * <p>This took {@code lines().findFirst()} literally, and a reply that opens with a newline
     * therefore logged as nothing at all. Measured over 1,544 verifier replies in this corpus:
     * 1,468 of them, 95 per cent, began with a blank line, so the progress note read
     * "<stage>: again — " with the objection missing. The objection itself was never lost, because
     * the whole judgement is spliced into the doer's feedback a few lines above; what was lost was
     * the one line a person reads to work out why a bump is still going.
     */
    private static String firstLine(String s) {
        return s == null ? "" : s.lines().map(String::strip).filter(l -> !l.isEmpty())
                .findFirst().orElse("");
    }

    /**
     * WHAT TO WRITE WHEN THERE IS NOTHING TO WRITE, and why it is not the same note.
     *
     * <p>{@link #verdictOf} reads silence as {@code again}, deliberately: defaulting it to
     * {@code done} would close a stage because a request came back empty. But then the note for a
     * reviewer who objected and the note for a reviewer who said nothing are the same sentence, and
     * they call for opposite responses. It happened 64 times in 1,544 calls, about one in
     * twenty-four, so it is worth telling apart.
     */
    private static String because(String judgement) {
        return judgement == null || judgement.isBlank()
                ? "the verifier answered nothing, which is read as again rather than as agreement"
                : firstLine(judgement);
    }
}
