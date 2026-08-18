package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASURE COMPLIANCE FOR BUMPS THAT SETTLED BEFORE ANYONE WAS MEASURING IT.
 *
 * <p>A bump records its own compliance as it settles, which covers everything from now on and
 * nothing that came before. The workspaces survive settlement, so the answer for the older ones is
 * still on disk rather than lost; this reads it.
 *
 * <p>IT IS NOT A ONE-OFF. Every row of a bill of materials is a claim about what a target needs,
 * and those move: a floor is raised, a spelling is added, a row is found to have no analogue on one
 * build system. Each of those makes every stored number stale, and a stale number is worse than an
 * absent one because it looks current. This is how the corpus is re-scored against the list as it
 * stands today, and it should be run whenever that list changes.
 *
 * <p>{@code Backfill <run-root>}, where the run root holds {@code ws/}, {@code results/} and the
 * manifests.
 */
final class Backfill {

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: Backfill <run-root>");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        Path results = root.resolve("results");
        Map<String, String[]> manifest = manifests(root);
        System.out.println("manifest rows: " + manifest.size());

        int measured = 0;
        int skipped = 0;
        try (var dirs = Files.list(root.resolve("ws"))) {
            for (Path ws : dirs.filter(Files::isDirectory).sorted().toList()) {
                String slug = ws.getFileName().toString();
                String[] row = manifest.get(slug);
                if (row == null) {
                    // A WORKSPACE WHOSE MANIFEST ROW IS GONE. The rerun manifests are transient and
                    // a workspace outlives them. Guessing the hop from the directory name would put
                    // a number against the wrong target, which is worse than leaving it absent.
                    System.out.println("  skip " + slug + ": no manifest row names it");
                    skipped++;
                    continue;
                }
                String bump = row[0] + "|" + row[1] + "|" + row[2] + "|" + row[3];
                Hop hop;
                try {
                    hop = new Hop(Integer.parseInt(row[2]), Integer.parseInt(row[3]));
                } catch (NumberFormatException notAHop) {
                    skipped++;
                    continue;
                }
                Path trace = results.resolve(bump.replaceAll("[^A-Za-z0-9]+", "_"))
                        .resolve("trace.jsonl");
                Bom.Compliance c = Bom.measure(ws, trace, hop);
                Bom.Compliance was = Bom.measureBefore(ws, trace, row[1], hop);
                Bom.record(results, bump, c, was);
                Bom.Movement moved = Bom.between(was, c);
                System.out.printf("  %-46s now %2d/%-2d %-4s   comparable %2d: %d issues -> %d%n",
                        slug, c.met(), c.applicable(),
                        c.percent() < 0 ? "  -" : c.percent() + "%",
                        moved.applied(), moved.missedBefore(), moved.missedAfter());
                measured++;
            }
        }
        System.out.println("measured " + measured + ", skipped " + skipped);
    }

    /**
     * Every manifest row this run root has ever read, keyed by the slug that names its workspace.
     *
     * <p>Later files win: a slug reused by a later sweep describes the workspace that is on disk
     * now, and the earlier row describes one that was deleted before this one was cloned.
     */
    private static Map<String, String[]> manifests(Path root) throws IOException {
        Map<String, String[]> rows = new LinkedHashMap<>();
        try (var files = Files.list(root)) {
            List<Path> sorted = files
                    .filter(p -> p.getFileName().toString().endsWith(".tsv"))
                    .sorted()
                    .toList();
            for (Path f : sorted) {
                for (String line : Files.readAllLines(f)) {
                    String[] cell = line.split("\t");
                    if (cell.length >= 5 && !cell[0].isBlank()) {
                        rows.put(cell[0], new String[] {cell[1], cell[2], cell[3], cell[4]});
                    }
                }
            }
        }
        return rows;
    }

    private Backfill() {
    }
}
