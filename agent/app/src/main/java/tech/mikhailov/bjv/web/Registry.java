package tech.mikhailov.bjv.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A REGISTRY OF BUMPS, PARSED AND MERGED WITHOUT SILENTLY LOSING A ROW.
 *
 * <p>The upload format is five comma-separated fields:
 *
 * <pre>
 * url, sha, from, to, key
 *
 * https://github.com/owner/name, bdc86ebe64e2…, 11, 17,
 * https://github.com/owner/other,             , 17, 21,
 * https://git.internal/team/thing, 9f1c2d…    , 11, 17, ghp_xxx
 * </pre>
 *
 * <p>EVERY COMMA IS MANDATORY, INCLUDING THE TRAILING ONE. `sha` and `key` are optional in the sense
 * that they may be blank, not in the sense that they may be missing: a row with three commas is a
 * row whose columns cannot be identified, and guessing which of the two optionals was omitted is how
 * a key ends up parsed as a JDK level. Five fields always, some of them empty.
 *
 * <p>A LINE THAT WILL NOT PARSE IS REPORTED BACK, NOT DROPPED. This is the sibling tool's rule for
 * the same job and it is the right one: a loader that quietly discards rows it dislikes gives back a
 * number smaller than the file and no way to find out which are missing.
 *
 * <p>THE KEY NEVER COMES BACK OUT. It is a credential, it is written to a file the dashboard does
 * not serve and cannot reach through any endpoint, and the upload's reply says only how many rows
 * carried one. The model settings page drew the same line one level up and no longer does, on a
 * decision recorded in Settings.model. These are a different kind of secret: one token per
 * repository, uploaded in bulk by whoever owns the corpus rather than typed by the reader looking
 * at the page, and no reveal button was ever asked for. The line stays here.
 *
 * <p>THE MERGE IS AN ATOMIC RENAME, NEVER A TRUNCATE. The sweep re-reads its manifest at the top of
 * every round while holding it open, and this project has already lost a 1439-row run to a truncate
 * landing under that descriptor. Renaming swaps the inode, so the round in flight finishes on the
 * file it opened.
 */
final class Registry {

    /**
     * One accepted row.
     *
     * <p>{@code repo} is derived from the URL and is the identity everything else is keyed by: the
     * bump key is {@code repo|sha|from|to} and every settlement in the corpus already uses it, so it
     * cannot become a URL without orphaning the record. The URL travels beside it for the clone.
     */
    record Row(String slug, String repo, String url, String sha, int from, int to, String key) {

        /** Blank means "whatever the default branch is when the lane clones it". */
        boolean pinned() {
            return !sha.isBlank();
        }

        /** The manifest is tab-separated and whitespace-split, so an empty column needs a mark. */
        String tsv() {
            return slug + "\t" + repo + "\t" + (pinned() ? sha : "-") + "\t" + from + "\t" + to;
        }

        /** Dedup is by repository and starting level, as the sweep and the queue both do it. */
        String identity() {
            return repo + "\t" + from;
        }
    }

    record Rejected(int line, String text, String why) {
    }

    record Parsed(List<Row> rows, List<Rejected> rejected) {

        boolean empty() {
            return rows.isEmpty();
        }

        /** How many rows carried a credential. The only thing said about keys, ever. */
        long keyed() {
            return rows.stream().filter(r -> !r.key().isBlank()).count();
        }

        /** How many rows will resolve their own commit, which is worth warning about. */
        long unpinned() {
            return rows.stream().filter(r -> !r.pinned()).count();
        }
    }

    private Registry() {
    }

    static Parsed parse(String text) {
        List<Row> rows = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            // -1 KEEPS THE TRAILING EMPTY FIELD. Without it "url,sha,11,17," splits to four and a
            // row whose key is deliberately blank is rejected for having too few columns, which is
            // the exact opposite of what the trailing comma is there to say.
            String[] c = line.split(",", -1);
            if (c.length != 5) {
                rejected.add(new Rejected(i + 1, raw, "needs exactly 5 comma-separated fields: "
                        + "url, sha, from, to, key (found " + c.length + "; every comma is "
                        + "mandatory, blanks are how you say a field is empty)"));
                continue;
            }
            String url = c[0].strip();
            String sha = c[1].strip();
            String key = c[4].strip();
            String repo = repoOf(url);
            if (repo.isEmpty()) {
                rejected.add(new Rejected(i + 1, raw,
                        "first field should be a repository URL, got " + describe(url)));
                continue;
            }
            if (!sha.isEmpty() && !sha.matches("[0-9a-fA-F]{7,40}")) {
                // A BRANCH NAME IS NOT A BLANK. Blank says "resolve the default branch now and
                // record what you got"; a branch name says "resolve it again next time", and the
                // same row would then mean a different tree on a re-run.
                rejected.add(new Rejected(i + 1, raw, "sha should be a commit or blank, got " + sha
                        + " (blank takes the default branch and records the commit it resolved)"));
                continue;
            }
            int from;
            int to;
            try {
                from = Integer.parseInt(c[2].strip());
                to = Integer.parseInt(c[3].strip());
            } catch (NumberFormatException notANumber) {
                rejected.add(new Rejected(i + 1, raw, "from and to should be JDK levels, got "
                        + describe(c[2]) + " and " + describe(c[3])));
                continue;
            }
            if (to <= from) {
                rejected.add(new Rejected(i + 1, raw,
                        "to must be above from, got " + from + " -> " + to));
                continue;
            }
            Row row = new Row(slugFor(repo, from), repo, url, sha, from, to, key);
            if (!seen.add(row.identity())) {
                rejected.add(new Rejected(i + 1, raw,
                        "already in this file: " + repo + " from " + from));
                continue;
            }
            rows.add(row);
        }
        return new Parsed(rows, rejected);
    }

    /**
     * {@code owner/name} out of a URL, in the forms people actually paste.
     *
     * <p>Https, ssh, with or without {@code .git}, with or without a trailing slash. A bare
     * {@code owner/name} is accepted too, because the old registry format used it and a reader who
     * has one open will paste a line from it to see what happens.
     */
    static String repoOf(String url) {
        String s = url.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = s.replaceFirst("^git\\+", "").replaceFirst("\\.git$", "").replaceAll("/+$", "");
        s = s.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        s = s.replaceFirst("^[^/@]+@", "");
        // scp-shaped ssh: host:owner/name
        s = s.replaceFirst("^([^/:]+):", "$1/");
        String[] parts = s.split("/");
        if (parts.length < 2) {
            return "";
        }
        String name = parts[parts.length - 1];
        String owner = parts[parts.length - 2];
        if (owner.isEmpty() || name.isEmpty() || owner.contains(".") && parts.length == 2) {
            // "github.com/name" is a host and a word, not an owner and a repository.
            return "";
        }
        return owner + "/" + name;
    }

    /** Something safe to put in an error message: never echo a whole line that may hold a key. */
    private static String describe(String field) {
        String s = field.strip();
        if (s.isEmpty()) {
            return "nothing";
        }
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }

    /** The workspace directory's name. Deterministic, so a re-upload does not make a second one. */
    private static String slugFor(String repo, int from) {
        return "up_" + from + "_" + Integer.toHexString(repo.hashCode()).replace("-", "");
    }

    /**
     * MERGE INTO THE MANIFEST THE SWEEP IS READING, so new work joins the next round.
     *
     * <p>Existing rows win: a repository already in the corpus at the same starting level keeps the
     * commit it was queued at, because re-pointing it under a running sweep would mean two halves of
     * one run measured different trees. An upload adds work; it does not move work already counted.
     *
     * @return how many rows were actually new
     */
    static int mergeInto(Path manifest, List<Row> rows) throws IOException {
        List<String> existing = Files.isRegularFile(manifest)
                ? Files.readAllLines(manifest) : List.of();
        Set<String> have = new LinkedHashSet<>();
        List<String> all = new ArrayList<>();
        for (String line : existing) {
            String[] c = line.strip().split("\\s+");
            if (c.length < 5) {
                continue;
            }
            if (have.add(c[1] + "\t" + c[3])) {
                all.add(line.stripTrailing());
            }
        }
        int added = 0;
        for (Row r : rows) {
            if (have.add(r.identity())) {
                all.add(r.tsv());
                added++;
            }
        }
        // The sweep's own key: case-folded repository, then hop. A byte sort puts every
        // uppercase-initial repository first, and the dashboard folds case, so the two would show
        // one corpus in two orders and the sweep would look like it was skipping rows.
        all.sort((a, b) -> {
            String[] x = a.split("\\s+");
            String[] y = b.split("\\s+");
            int byRepo = x[1].compareToIgnoreCase(y[1]);
            if (byRepo != 0) {
                return byRepo;
            }
            int exact = x[1].compareTo(y[1]);
            return exact != 0 ? exact : Integer.compare(level(x, 3), level(y, 3));
        });
        rename(manifest, String.join("\n", all) + (all.isEmpty() ? "" : "\n"));
        return added;
    }

    private static int level(String[] row, int at) {
        try {
            return Integer.parseInt(row[at]);
        } catch (RuntimeException notANumber) {
            return 0;
        }
    }

    /**
     * WHERE A ROW WAS CLONED FROM, when it is not the GitHub URL the repository name implies.
     *
     * <p>The manifest carries {@code owner/name} because the whole record is keyed by it. That is
     * lossy for anything not on github.com, so the origin is kept beside it and the lane consults it
     * before falling back to the obvious URL.
     */
    static void recordOrigins(Path origins, List<Row> rows) throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        if (Files.isRegularFile(origins)) {
            for (String line : Files.readAllLines(origins)) {
                String[] c = line.split("\t", 2);
                if (c.length == 2) {
                    all.put(c[0], c[1]);
                }
            }
        }
        for (Row r : rows) {
            all.putIfAbsent(r.repo(), r.url());
        }
        StringBuilder out = new StringBuilder();
        all.forEach((repo, url) -> out.append(repo).append('\t').append(url).append('\n'));
        rename(origins, out.toString());
    }

    /**
     * THE CREDENTIALS, WRITTEN WHERE THE DASHBOARD CANNOT REACH THEM.
     *
     * <p>This file goes in the run root and not in {@code results/}: the results directory is what
     * the page serves, and one careless endpoint that lists a directory would publish every token in
     * the corpus. Owner-only permissions on top, because the run root is also a bind mount.
     *
     * <p>Nothing reads this back out through the API. There is no endpoint that returns it and no
     * field in any response that carries it — the upload reply says how many rows had one, and that
     * is the whole of what a reader is told.
     */
    static void recordKeys(Path credentials, List<Row> rows) throws IOException {
        List<Row> keyed = rows.stream().filter(r -> !r.key().isBlank()).toList();
        if (keyed.isEmpty()) {
            return;
        }
        Map<String, String> all = new LinkedHashMap<>();
        if (Files.isRegularFile(credentials)) {
            for (String line : Files.readAllLines(credentials)) {
                String[] c = line.split("\t", 2);
                if (c.length == 2) {
                    all.put(c[0], c[1]);
                }
            }
        }
        // A re-upload REPLACES a key: unlike a commit, a rotated token is meant to take effect.
        for (Row r : keyed) {
            all.put(r.repo(), r.key());
        }
        StringBuilder out = new StringBuilder();
        all.forEach((repo, key) -> out.append(repo).append('\t').append(key).append('\n'));
        rename(credentials, out.toString());
        try {
            Files.setPosixFilePermissions(credentials,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException notPosix) {
            // A filesystem without POSIX modes is not a reason to refuse the upload; the file is
            // still outside the served directory, which is the guarantee that matters.
        }
    }

    /** Write beside, then rename over: see the class comment on the run this cost. */
    private static void rename(Path target, String content) throws IOException {
        Path staged = target.resolveSibling(target.getFileName() + ".staged");
        Files.writeString(staged, content, StandardCharsets.UTF_8);
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
