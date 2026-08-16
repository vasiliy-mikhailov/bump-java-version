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
    record Stage(String title, String within, List<Step> steps, String repeats, String reads) {

        Stage(String title, String within, List<Step> steps) {
            this(title, within, steps, "", "");
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
        return new Stage(s.title(), s.within(), s.steps(), repeats, s.reads());
    }

    /**
     * WHICH BILL OF MATERIALS A STAGE WORKS FROM, which is the same split the lists are kept in.
     *
     * <p>The two halves are different kinds of claim rather than two timings of one. What ENABLES
     * the bump is a precondition: a Lombok that cannot read the new class file kills javac before
     * anything else runs, and below one of those the bump does not happen at all. What HARDENS the
     * result is polish on a project that already builds and tests green, where the patch releases
     * carry the CVE fixes and nothing is load-bearing for the move itself.
     *
     * <p>Naming it here is what connects the two pages. A reader looking at before-pins can see
     * which list it is working to without knowing that the phase happens to run before the JDK
     * moves, and a reader editing a list can see which agent will act on what they typed.
     */
    private static Stage reading(Stage s, String reads) {
        return new Stage(s.title(), s.within(), s.steps(), s.repeats(), reads);
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
        return deterministic(title, "");
    }

    private static Stage deterministic(String title, String within) {
        return new Stage(title, within, List.of(runs(title)));
    }

    /**
     * Every stage, in the order the bump reaches them.
     *
     * <p>REPAIR LIVES INSIDE THE MODULE WALK NOW. It used to sit after every module had been
     * bumped, as the other half of a sixteen-turn loop with the repository gate, and that loop
     * existed only because repair was repository-wide: the gate had to keep re-running to find out
     * whether the last repair had worked. A break in the first module surfaced as a reactor error
     * after the last one, with no obvious owner and two hundred lines of log.
     *
     * <p>So a module is now pinned, bumped, compiled and repaired before the walk moves on, and the
     * repository gate becomes a single scoring step rather than the head of a loop. What it decides
     * is what only it can: the passing set against the baseline, and the lowest bytecode level any
     * module actually emits. Neither is knowable one module at a time.
     *
     * <p>WHAT THIS GIVES UP, deliberately and with the cost known: a module that compiles alone and
     * breaks the reactor because a sibling moved under it now has no repair path. Every module gate
     * is green, the repository gate is red, and nothing tries again. Some bumps that pass today will
     * fail. The argument for taking that is that a cross-module break is usually a bad edit, and a
     * bad edit is better failed loudly than papered over sixteen times.
     */
    static List<Stage> stages() {
        return List.of(
                triplet("survey"),
                deterministic("baseline"),
                triplet("security-before"),
                triplet("module-filter"),
                // The doing of the module stage is the walk below it.
                repeating(aroundDeterministic("modules", "", "the module walk"), "per module"),
                reading(triplet("before-pins", "modules"), "enables"),
                // The JDK move itself, which reads no list: it is the thing the two lists are
                // either side of.
                triplet("bump", "modules"),
                // A gate one module wide. Compile only: test conservation is a whole-suite fact
                // measured against the baseline, so a per-module run cannot decide it and the
                // repository gate has to run the suite anyway.
                // NO AGENTS. Compiling one module and reading the exit code needs no judgement,
                // and a planner and a critic per module per turn is a lot of tokens spent agreeing
                // with a compiler.
                repeating(deterministic("module-gate", "modules"),
                        "until green, or the turns run out"),
                repeating(aroundDeterministic("module-repair", "modules", "a campaign of steps"),
                        "only when the module-gate is red"),
                // 6 PER CAMPAIGN AND TWO CAMPAIGNS, so twelve, and the bump's own allowance
                // caps the lot: the label said six and meant half of one module's worst case.
                repeating(triplet("module-repair-step", "module-repair"),
                        "up to 12 per module, 192 per bump"),
                // AFTER THE REPAIR, NOT BEFORE IT. Hardening polishes a module that already
                // compiles; asking it of one that does not is asking the wrong question.
                reading(triplet("after-pins", "modules"), "hardens"),
                // THE SCORER, AND NO LONGER A LOOP. It compares the passing set to the baseline and
                // reads the effective target, which is all it ever decided; the turns around it
                // were repair's, and repair has moved.
                deterministic("gate"),
                repeating(triplet("security-after"), "only after a green gate"),
                repeating(triplet("verdict"), "only when the gate never went green"),
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
