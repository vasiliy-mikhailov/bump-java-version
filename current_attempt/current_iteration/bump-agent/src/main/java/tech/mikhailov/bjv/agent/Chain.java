package tech.mikhailov.bjv.agent;

import java.util.List;

/**
 * THE CHAIN, DECLARED ONCE, IN THE ORDER {@link Bump} RUNS IT.
 *
 * <p>This existed twice: as factory methods in {@link Agents} and again as a hand-typed array in
 * {@link Dashboard}. Two copies of one fact drift, and this pair did. The dashboard went on
 * advertising a {@code prepare} stage with a {@code preparer} and a {@code prepare-critic} for hours
 * after all three were deleted, and showed none of the agents added in their place, so the page that
 * exists to answer "what is this bump doing" was describing a chain that no longer ran. Nothing
 * failed; a reader was simply told something untrue.
 *
 * <p>So the order and the grouping live here, the prompts and tools live in {@link Agents}, and a
 * test binds them: every agent slot below must have a definition, and every definition must appear
 * below. Neither file can go stale without the build saying so.
 *
 * <p>EVERY STAGE IS A TRIPLET: plan, do, verify. That is the shape of the whole system rather than a
 * convention some stages follow. A pair collapses planning into doing, and the agent that chose an
 * approach is then the one asked whether the approach was right, which is the bias the third role
 * exists to remove. Where a doer is a deterministic step rather than an agent, it is marked as one:
 * the module pass and the troubleshoot campaign both "do" by running a sub-chain, and pretending a
 * model sits there would be its own untruth.
 */
final class Chain {

    /** What a step is: an agent this harness prompts, or a deterministic step it runs itself. */
    enum Kind { AGENT, DETERMINISTIC }

    /** One step of one stage. The role is part of the name, because the name is what a reader sees. */
    record Step(String name, String role, Kind kind) {

        boolean agent() {
            return kind == Kind.AGENT;
        }
    }

    /**
     * One stage, and the steps it runs in order.
     *
     * <p>{@code within} names the stage this one is nested inside, so the page can show the module
     * passes under the module stage rather than as peers of it. Empty for a top-level stage.
     */
    record Stage(String title, String within, List<Step> steps, String repeats) {

        Stage(String title, String within, List<Step> steps) {
            this(title, within, steps, "");
        }

        boolean nested() {
            return !within.isBlank();
        }
    }

    /**
     * HOW OFTEN A STAGE RUNS, AND WHETHER IT RUNS AT ALL.
     *
     * <p>Nesting says where a stage sits and says nothing about how many times it happens, and the
     * two are different questions that a reader will answer wrongly from the first. Under modules,
     * only bump walks the module list; both pin phases run once for the repository and are handed
     * the module list as text. Drawn as three peers inside a loop, they read as three per-module
     * passes, which is what the stage's own deterministic step is called.
     *
     * <p>And troubleshoot is not a stage that follows the module work. It is the repair arm of a
     * turn loop with the gate: the gate builds and tests the whole repository, and troubleshoot
     * runs only when that comes back red, up to sixteen times. A page that lists it after modules
     * with nothing said implies it always runs and runs once, and it does neither.
     */
    private static Stage repeating(Stage s, String repeats) {
        return new Stage(s.title(), s.within(), s.steps(), repeats);
    }

    private Chain() {
    }

    private static Step planner(String stage) {
        return new Step(stage + "-planner", "planner", Kind.AGENT);
    }

    private static Step doer(String stage) {
        return new Step(stage + "-doer", "doer", Kind.AGENT);
    }

    private static Step verifier(String stage) {
        return new Step(stage + "-verifier", "verifier", Kind.AGENT);
    }

    /** A doer that is a deterministic step: a sub-chain, a build, or a read. */
    private static Step runs(String name) {
        return new Step(name, "doer", Kind.DETERMINISTIC);
    }

    private static Stage triplet(String title) {
        return new Stage(title, "", List.of(planner(title), doer(title), verifier(title)));
    }

    private static Stage triplet(String title, String within) {
        return new Stage(title, within, List.of(planner(title), doer(title), verifier(title)));
    }

    /** A stage whose doing is deterministic but whose plan and verdict are still judged. */
    private static Stage aroundDeterministic(String title, String within, String step) {
        return new Stage(title, within, List.of(planner(title), runs(step), verifier(title)));
    }

    /** A stage that is only a deterministic step: a fact, with nothing to plan or dispute. */
    private static Stage deterministic(String title) {
        return new Stage(title, "", List.of(runs(title)));
    }

    /** Every stage, in the order the bump reaches them. */
    static List<Stage> stages() {
        return List.of(
                triplet("survey"),
                deterministic("baseline"),
                triplet("security-before"),
                triplet("module-filter"),
                // The doing of the module stage is the three ordered passes below it.
                aroundDeterministic("modules", "", "the three passes"),
                repeating(triplet("before-pins", "modules"), "once for the repository"),
                repeating(triplet("bump", "modules"), "once per module"),
                repeating(triplet("after-pins", "modules"), "once for the repository"),
                deterministic("gate"),
                // The doing of the troubleshoot stage is a campaign of the steps below it.
                repeating(aroundDeterministic("troubleshoot", "", "a campaign of steps"),
                        "only when the gate is red, up to 16 turns"),
                repeating(triplet("step", "troubleshoot"), "once per ordered step"),
                repeating(triplet("security-after"), "only after a green gate"),
                triplet("verdict"),
                triplet("estimator"));
    }

    /** Every agent the chain names, in order. What {@link Agents} must define, and only that. */
    static List<String> agentNames() {
        return stages().stream()
                .flatMap(s -> s.steps().stream())
                .filter(Step::agent)
                .map(Step::name)
                .toList();
    }
}
