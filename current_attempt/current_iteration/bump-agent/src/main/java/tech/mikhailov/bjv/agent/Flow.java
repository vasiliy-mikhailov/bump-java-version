package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * THE SHAPE IS THE PROGRAM, RATHER THAN A DESCRIPTION OF IT.
 *
 * <p>{@link Chain} exists because the structure of a bump lives in two places: declared there, and
 * carried out in {@link Bump}. Two copies of one fact drift, and this pair has: a page drew a loop
 * between two stages that had never joined, a label said a phase ran once for the repository days
 * after it became per module, and four prompts opened by telling agents a gate had failed that no
 * longer ran before them. Every one of those was found by reading, not by anything failing.
 *
 * <p>The fix is not another test binding the two. It is to stop having two. An agent is
 * {@code String run(String)}; a triad is three of those with a loop; a sequence is several of them
 * in order; a walk is one of them per item; plain code is one of them that calls no model. All the
 * same interface, so they compose, and the composition IS the shape. Nothing has to be kept in step
 * with it because there is nothing else.
 *
 * <p>THIS IS SEQUENCE, SELECTION AND ITERATION, and that is the whole point. Structured programming
 * replaced the jump with three combinators over one notion of statement. These are three
 * combinators over one notion of agent, and they buy the same thing: a text whose indentation is
 * its control flow, and a worst case you compute by reading rather than by simulating.
 *
 * <p>Every node carries {@link Agents.Agent#inside()}, so the picture is derived by walking the
 * thing that runs. A diagram drawn beside a program can be wrong and stay wrong; a diagram that IS
 * the program cannot.
 */
final class Flow {

    private Flow() {
    }

    /** A step of plain code, which is an agent like any other and calls no model. */
    @FunctionalInterface
    interface Step {
        String run(String task) throws IOException;
    }

    /**
     * A NAMED NODE. Everything here is one, because a node without a name cannot be traced, cannot
     * be drawn, and cannot be pointed at in a bug report.
     */
    private abstract static class Node implements Agents.Agent {
        private final String label;

        Node(String label) {
            this.label = label;
        }

        @Override
        public String name() {
            return label;
        }
    }

    /** Plain code as an agent. The gate, a build, a scan: things with no model in them. */
    static Agents.Agent code(String name, Step body) {
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                return body.run(task);
            }
        };
    }

    /**
     * One after another, each handed the same brief.
     *
     * <p>THE LAST WORD IS THE SEQUENCE'S WORD, which is the same rule a triad's doer follows. A
     * sequence that concatenated every step's answer would hand the next reader a transcript rather
     * than a result, and every caller would then have to decide which part of it mattered.
     */
    static Agents.Agent seq(String name, Agents.Agent... steps) {
        List<Agents.Agent> all = List.of(steps);
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                String last = "";
                for (Agents.Agent step : all) {
                    last = step.run(task);
                }
                return last;
            }

            @Override
            public List<Agents.Agent> inside() {
                return all;
            }
        };
    }

    /**
     * The same work once per item, with the item's own name in the brief.
     *
     * <p>The list is a supplier rather than a list, because what a walk walks is decided by a stage
     * that ran earlier: the module filter chooses the modules and it is itself in the sequence.
     *
     * <p>{@code inside()} reports the body built for a null item, so the picture can be drawn before
     * anything has run. That is a real limitation and an honest one: a shape is what the program can
     * do, not what one execution did.
     */
    static <T> Agents.Agent each(String name, Supplier<List<T>> items,
                                 Function<T, String> label, Function<T, Agents.Agent> body) {
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                String last = "";
                for (T item : items.get()) {
                    last = body.apply(item).run(task + "\n\nThis pass is for: " + label.apply(item));
                }
                return last;
            }

            @Override
            public List<Agents.Agent> inside() {
                return List.of(body.apply(null));
            }
        };
    }

    /**
     * Until it says it is done, or the turns run out.
     *
     * <p>The condition is asked BEFORE each turn and again after the body, so a block whose work is
     * already unnecessary costs nothing. A loop that always ran once before checking is how a
     * repository that needed no repair still paid for a repair planner.
     */
    static Agents.Agent loop(String name, int turns, BooleanSupplier again, Agents.Agent body) {
        int bound = Math.max(1, turns);
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                String last = "";
                for (int turn = 1; turn <= bound && again.getAsBoolean(); turn++) {
                    last = body.run(task + "\n\nTurn " + turn + " of at most " + bound + ".");
                }
                return last;
            }

            @Override
            public List<Agents.Agent> inside() {
                return List.of(body);
            }
        };
    }

    /** Only when the condition holds. Selection, and the reason a stage can say "only after a green gate". */
    static Agents.Agent when(String name, BooleanSupplier cond, Agents.Agent body) {
        return new Node(name) {
            @Override
            public String run(String task) throws IOException {
                return cond.getAsBoolean() ? body.run(task) : "";
            }

            @Override
            public List<Agents.Agent> inside() {
                return List.of(body);
            }
        };
    }

    /**
     * THE SHAPE, AS TEXT, WALKED OFF THE THING THAT RUNS.
     *
     * <p>This is the whole argument in one method. The picture is not drawn beside the program and
     * kept in step by hand; it is the program, printed. It cannot point at two stages that never
     * joined, because it has no coordinates to get wrong.
     */
    static String shape(Agents.Agent root) {
        StringBuilder out = new StringBuilder();
        draw(root, 0, out);
        return out.toString();
    }

    private static void draw(Agents.Agent node, int depth, StringBuilder out) {
        if (!node.name().isEmpty()) {
            out.append("    ".repeat(depth)).append(node.name()).append('\n');
        }
        int next = node.name().isEmpty() ? depth : depth + 1;
        for (Agents.Agent child : node.inside()) {
            draw(child, next, out);
        }
    }

    /** Every named node, in the order the program reaches them. What a page or a test walks. */
    static List<String> names(Agents.Agent root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Agents.Agent node, List<String> out) {
        if (!node.name().isEmpty()) {
            out.add(node.name());
        }
        for (Agents.Agent child : node.inside()) {
            collect(child, out);
        }
    }
}
