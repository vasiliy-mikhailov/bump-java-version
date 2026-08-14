package tech.mikhailov.bjv.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE MECHANIZED TROUBLESHOOTING TABLE — the reflect loop's enumerable half.
 *
 * <p>Rung-1 decomposed the reflect loop's value: roughly half of what iteration recovers is
 * signature -> known fix, no judgement involved. That half belongs here, where it costs no tokens
 * and cannot be stochastically fumbled. What this table cannot match falls through to the fixer
 * agent, which is exactly the residue a model is FOR.
 *
 * <p>Every row's fix carries the evidence that put it here. A row nobody can trace to a measured
 * failure is folklore, and folklore is how the 11->17 skill briefly told agents to floor Lombok to
 * a JaCoCo version.
 */
final class Walls {

    private final Path ws;
    private final Set<String> applied = new LinkedHashSet<>();

    Walls(Path ws) {
        this.ws = ws;
    }

    static boolean isPom(Path p) {
        return p.getFileName() != null && p.getFileName().toString().equals("pom.xml");
    }

    static List<Path> poms(Path ws) throws IOException {
        try (var s = Files.walk(ws)) {
            return s.filter(Walls::isPom)
                    .filter(p -> !p.toString().contains("/target/")
                            && !p.toString().contains("/node_modules/"))
                    .toList();
        }
    }
}
