package tech.mikhailov.bjv.bump;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import tech.mikhailov.ratchet.config.Prompts;

/**
 * WHAT A BUMP PUTS ITS NAME TO: the prompts its agents are handed, and the lists they are scored
 * against.
 *
 * <p>{@link Version} used to work this out itself, calling {@link Agents#forHop} and
 * {@link Bom#textFor} directly. That is backwards. Which pipeline produced a row
 * is a general question with a general answer, and what a pipeline consists of is not: the day
 * this program grows a second thing agents are handed, the fingerprint class is not where that
 * belongs. So the parts arrive from this side, and {@link Version} does the hashing and the
 * formatting.
 *
 * <p>THE HOP ARRIVES AS A KEY and is turned back into a {@link Hop} here, which is the only side
 * that knows a key is two Java version numbers.
 */
final class Fingerprint implements Version.Parts {

    /**
     * THE PIPELINE FIELDS FOR ONE BUMP, as a string of JSON fields ready to append.
     *
     * <p>The record writer used to work this out itself. To do that it had to know that a bump key
     * is four fields separated by pipes and that the third and fourth of them are a Java version
     * pair, which is this package's grammar and nobody else's: a general record writer carrying
     * one pipeline's key format is a record writer only that pipeline can use. So it takes a
     * supplier now, and this is the supplier.
     *
     * <p>Asked at write time and at most once per lane, never here. NO LEADING COMMA: the writer
     * puts one in when the answer is not empty, and a second one makes a row nothing can parse.
     */
    static Supplier<String> provenanceOf(String bump, Path settlements) {
        return () -> {
            String[] p = bump.split("\\|");
            if (p.length < 4) {
                return "";
            }
            Path root = settlements.getParent() == null ? settlements : settlements.getParent();
            return Version.fields(p[2] + "-" + p[3], root, OF_A_BUMP);
        };
    }

    /** The one instance. It holds nothing, and every caller is asking the same question. */
    static final Fingerprint OF_A_BUMP = new Fingerprint();

    private Fingerprint() {
    }

    /**
     * Every system prompt the hop will use, in a stable order, overrides included.
     *
     * <p>THE EDIT, WHERE THERE IS ONE. forHop builds prompts through define, which deliberately
     * does not consult the override store: that catalogue is what the settings page shows beside an
     * edit, so it has to be the code's own text. The live agent is built by runtime, which consults
     * it. Hashing the catalogue alone was therefore blind to the exact case the fingerprint exists
     * for, and a test caught it.
     */
    @Override
    public String prompts(String hop, Path results) {
        List<String> all = Agents.forHop(hopOf(hop), results).stream()
                .map(d -> {
                    String edited = Prompts.override(d.name(), hop);
                    return d.name() + " " + (edited.isBlank() ? d.systemPrompt() : edited);
                })
                .sorted()
                .toList();
        return String.join("", all);
    }

    /** Both lists for the hop, overrides included. */
    @Override
    public String boms(String hop) {
        Hop asked = hopOf(hop);
        return Bom.textFor(asked, "enables").text() + Bom.textFor(asked, "hardens").text();
    }

    /**
     * The key back as a hop.
     *
     * <p>A key that is not one throws, and {@link Version} answers with no fingerprint rather than
     * costing a settlement its row.
     */
    private static Hop hopOf(String key) {
        String[] pair = key.split("-");
        return Hop.of(pair[0], pair[1]);
    }
}
