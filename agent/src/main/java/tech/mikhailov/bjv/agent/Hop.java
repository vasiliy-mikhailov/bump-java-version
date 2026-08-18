package tech.mikhailov.bjv.agent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ONE PRESCRIBED MOVE, AND EVERYTHING THAT FOLLOWS FROM IT.
 *
 * <p>The hop arrives in the manifest and nothing may change it: it is the experiment's independent
 * variable. What it decides, though, was scattered. Which OpenRewrite recipes to run came from a
 * private method in {@link Migrate}, which version floors apply came from {@link Floors}, and which
 * of those an agent was told about came from prose typed into a prompt that named every rule for
 * every hop. An 8-to-11 bump was handed the Kotlin rule for JDK 25 and the jakarta rule for 21, both
 * of which are unreachable from where it stands.
 *
 * <p>A rule that cannot fire is not merely wasted context. It is an invitation to apply it anyway,
 * and the corpus has a troubleshooter that raised a Gradle wrapper to a version that was never
 * published because nothing told it which versions were real.
 *
 * <p>So the hop answers those questions itself, from the same tables the code applies, and the
 * agents built for it are told only what it can reach.
 */
public record Hop(int from, int to) {

    /** The LTS rungs, which is what "one hop" is measured in even when a hop spans several. */
    private static final int[] LTS = {8, 11, 17, 21, 25};

    static Hop of(String from, String to) {
        return new Hop(Integer.parseInt(from), Integer.parseInt(to));
    }

    @Override
    public String toString() {
        return from + " -> " + to;
    }

    /**
     * Whether this hop passes through an LTS rung, which is not the same as landing on it.
     *
     * <p>A prescribed hop need not be one step: 11 to 25 crosses 17 and 21, and the walls at those
     * levels do not go away because nothing stopped there.
     */
    boolean crosses(int rung) {
        return rung > from && rung <= to;
    }

    /** The rungs this hop passes through, in order. */
    List<Integer> rungs() {
        return java.util.Arrays.stream(LTS).filter(this::crosses).boxed().toList();
    }

    /**
     * The floors as instructions, for the agents that are told to apply them.
     *
     * <p>One line per artifact, with the version this hop actually needs rather than a conditional
     * covering every target. At 11 that is seven rules; the prompt used to carry all sixteen.
     */
    String floorsAsInstructions() {
        return Floors.forTarget(to).lines()
                .filter(l -> !l.isBlank())
                .map(l -> "                - " + l.strip())
                .collect(Collectors.joining("\n"));
    }

    /** Whether this hop crosses the jakarta boundary, which is the highest-variance thing it can do. */
    boolean crossesJakarta() {
        return to >= 21;
    }
}
