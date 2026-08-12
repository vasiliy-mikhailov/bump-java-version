package tech.mikhailov.bjv.agent;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explore each bump: what the fleet is doing, what any one bump did, and the feedback form that
 * turns a traced (prompt, reply) pair into a labelled training example.
 *
 * <p>One process, no framework, no build step for the UI. The dashboard is a READER; the only
 * thing it ever writes is a feedback row, because a dashboard that can edit its subject is a
 * second orchestrator nobody audits.
 *
 * <p>IT SHOWS THE GATE'S THREE LEGS SEPARATELY, because that is what a bump IS. A green build
 * proves the first leg only, and a page that collapses "compiled", "kept its tests" and "actually
 * reached the target" into one word throws away the distinction the whole scorer exists to make.
 * The semaphore on every row is those three lights, in that order.
 *
 * <p>IT IS SERVED ON A PUBLIC NAME, so the assumptions a localhost tool gets for free are stated
 * here instead. The slug that selects a bump is an OPAQUE KEY matched against an allowlist, and
 * the resolved file is re-checked to be under the results directory, because {@link Path#resolve}
 * on an absolute argument discards the base. The write endpoint is bounded and token-gated. What
 * the pages render is untrusted throughout, being model output and third-party repository source,
 * so every value is escaped on the way out.
 */
public final class Dashboard {

    /** A feedback note is a sentence, not a payload. Anything larger is not a note. */
    private static final int MAX_FEEDBACK_BYTES = 64 * 1024;

    /** Only this shape may select a bump. Everything else is not a slug, whatever it looks like. */
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9_-]{1,200}");

    /**
     * THE CHAIN, DECLARED IN THE ORDER {@link Bump} RUNS IT.
     *
     * <p>Rendered whole on every bump page, including the stages a bump never reached, because a
     * strip that lists only who has spoken shows a chain of ten as a chain of two and hides where
     * the run actually stopped. A dim stage is information: it is the answer to "how far did this
     * get", which is the first question anyone asks.
     *
     * <p>Each entry is {phase, producer, critic}; the closers have no critic, since nothing
     * downstream branches on their answer.
     */
    private static final String[][] CHAIN = {
            {"survey", "surveyor", "survey-critic"},
            {"security-before", "security-before", "security-before-critic"},
            {"prepare", "preparer", "prepare-critic"},
            {"bump", "bumper", "bump-critic"},
            {"troubleshoot", "troubleshooter", "trouble-critic"},
            {"security-after", "security-after", "security-after-critic"},
            {"close", "verdict", "estimator"},
    };

    private static final String CSS = """
            *{box-sizing:border-box}
            body{margin:0;font:13px/1.6 ui-monospace,SFMono-Regular,Menlo,monospace;
            background:#0d1117;color:#c9d1d9}
            a{color:#58a6ff;text-decoration:none}a:hover{text-decoration:underline}
            header{padding:16px 24px;border-bottom:1px solid #21262d}
            h1{margin:0;font-size:14px;font-weight:600}
            .sub{color:#7d8590;font-size:12px;margin-top:3px}
            .bar{height:4px;background:#161b22}
            .bar i{display:block;height:100%;background:linear-gradient(90deg,#1f6feb,#3fb950)}
            .counts{display:flex;flex-wrap:wrap;gap:8px;padding:14px 24px}
            .c{padding:6px 12px;border:1px solid #21262d;border-radius:6px;background:#161b22}
            .c b{font-size:17px;display:block}.c span{color:#7d8590;font-size:11px}
            table{width:100%;border-collapse:collapse}
            th{text-align:left;color:#7d8590;font-weight:500;font-size:11px;text-transform:uppercase;
            letter-spacing:.06em;padding:9px 24px;border-bottom:1px solid #21262d}
            td{padding:9px 24px;border-bottom:1px solid #161b22;vertical-align:top}
            tr:hover td{background:#0f141a}
            .k{color:#7d8590;font-size:11px}
            .s{padding:2px 9px;border-radius:20px;font-size:11px;white-space:nowrap;
            display:inline-block}
            .PASS{background:#132e1a;color:#3fb950}
            .bumping{background:#122033;color:#58a6ff}
            .bumping::before{content:"\\25cf ";animation:p 1.4s ease-in-out infinite}
            @keyframes p{0%,100%{opacity:1}50%{opacity:.25}}
            .no-baseline,.not-a-bump{background:#161b22;color:#8b949e}
            .queued{background:#0d1117;color:#6e7681;border:1px solid #21262d}
            .interrupted{background:#20161f;color:#bc8cff;border:1px solid #21262d}
            .blocked-dependency,.behavior-change{background:#2b2011;color:#d29922}
            .infra{background:#2d1618;color:#f85149}
            .sema{display:flex;gap:5px;margin-top:5px}
            .sema i{width:9px;height:9px;border-radius:50%;display:block;border:1px solid #30363d}
            .sema i.green{background:#3fb950;border-color:#3fb950;box-shadow:0 0 5px #3fb95066}
            .sema i.red{background:#f85149;border-color:#f85149;box-shadow:0 0 5px #f8514966}
            .sema i.none{background:transparent;border-style:dashed}
            .hop{color:#a371f7}
            .cve-down{color:#3fb950}.cve-up{color:#f85149}.cve-flat{color:#8b949e}
            .t-ok{color:#3fb950}.t-low{color:#f85149}
            .deps{margin:0 24px 8px;border:1px solid #21262d;border-radius:6px;padding:8px 12px}
            .deps summary{font-size:12px;color:#c9d1d9}
            .deps table{margin-top:8px}
            .deps th{padding:4px 8px;font-size:10px}
            .deps td{padding:3px 8px;font-size:12px;border-bottom:1px solid #161b22}
            .dep.moved td:nth-child(3){color:#3fb950}
            .dep.added td{background:#0f1a12}.dep.gone td{background:#1a0f11}
            tr.dep:not(.moved):not(.added):not(.gone) td{color:#6e7681}
            td.latest{color:#8b949e;font-size:12px;max-width:52ch}
            .empty{padding:48px 24px;color:#7d8590}
            .back{padding:14px 24px;display:block}
            .pipe{display:flex;gap:10px;flex-wrap:wrap;align-items:flex-end;padding:12px 24px;
            border-bottom:1px solid #21262d}
            .allpill{padding:5px 11px;border-radius:6px;font-size:12px;color:#8b949e;
            border:1px solid #21262d;align-self:center}
            .allpill.on{background:#1f6feb;color:#fff;border-color:#1f6feb}
            .stage{display:flex;align-items:center;gap:5px;padding:6px 9px;border-radius:8px;
            border:1px solid #21262d;background:#0f141a;position:relative}
            .stage.dim{opacity:.42}
            .stagename{position:absolute;top:-8px;left:9px;font-size:9px;color:#7d8590;
            text-transform:uppercase;letter-spacing:.08em;background:#0d1117;padding:0 4px}
            .ag{padding:4px 9px;border-radius:6px;font-size:12px;color:#8b949e;
            background:#161b22;white-space:nowrap}
            a.ag:hover{background:#21262d;text-decoration:none}
            .ag b{color:#c9d1d9;font-weight:600}
            .ag.never{background:transparent;border:1px dashed #30363d;color:#6e7681}
            .ag.on{background:#1f6feb;color:#fff}.ag.on b{color:#fff}
            .link{color:#30363d;font-size:13px}
            .link.looped{color:#d29922;font-weight:700}
            .funnel{display:flex;gap:0;flex-wrap:wrap;padding:0 24px 14px}
            .fstep{padding:6px 12px;border:1px solid #21262d;background:#161b22;
            border-right:none;font-size:11px;color:#7d8590}
            .fstep:last-child{border-right:1px solid #21262d}
            .fstep b{display:block;font-size:15px;color:#c9d1d9}
            .ev{border-left:2px solid #21262d;margin:0 24px;padding:12px 0 12px 16px}
            .ev.asked{border-color:#58a6ff}.ev.built{border-color:#d29922}
            .ev.settled{border-color:#3fb950}.ev.failed{border-color:#f85149}
            .ev.tool{border-color:#30363d}.ev.applied{border-color:#a371f7}
            .ev.thought{border-color:#8957e5;background:#0f0d14}
            .cut{color:#d29922;font-size:11px;margin-top:4px}
            .who{color:#58a6ff;font-weight:600}
            .kind{color:#7d8590;font-size:11px;text-transform:uppercase;letter-spacing:.06em;
            margin-left:8px}
            pre{white-space:pre-wrap;word-break:break-word;background:#161b22;
            border:1px solid #21262d;border-radius:6px;padding:10px;margin:8px 0;overflow-x:auto;
            font-size:12px;line-height:1.5}
            details{margin:6px 0}
            summary{cursor:pointer;color:#7d8590;font-size:11px;user-select:none}
            summary:hover{color:#c9d1d9}
            .rate{margin-top:12px;border-top:1px dashed #21262d;padding-top:10px}
            .rate input[name=note]{width:70%;background:#0d1117;color:#c9d1d9;
            border:1px solid #30363d;border-radius:6px;padding:6px;font:inherit;font-size:12px}
            .rate button{background:#0d1117;border:1px solid #1f6feb;color:#58a6ff;
            border-radius:6px;padding:5px 12px;font:inherit;font-size:11px;cursor:pointer}
            """;

    /**
     * Live updates over one event stream rather than a timer: the index re-renders (it holds no
     * reader state) and a bump page appends only what it has not seen, so a fold left open and the
     * scroll position both survive an update.
     */
    private static final String LIVE = """
            <script>
            (function(){
              var src = new EventSource('events');
              var here = location.pathname + location.search;
              var isList = !/\\/bump$/.test(location.pathname);
              src.onmessage = function(m){
                var n = JSON.parse(m.data);
                var seen = +(document.body.dataset.events || 0);
                if (isList) {
                  // Refresh on a state change, or when a minute has passed with the run still
                  // producing. Reacting to every event would re-render a 1439-row page on each of
                  // a thousand tool calls; reacting only to settlements leaves the page frozen for
                  // the twenty minutes a bump spends between them, which reads as broken.
                  var s = +(document.body.dataset.settled || 0);
                  var last = +(document.body.dataset.at || 0), now = Date.now();
                  if (n.settled <= s && now - last < 60000) return;
                  document.body.dataset.settled = n.settled;
                  document.body.dataset.at = now;
                } else if (n.events <= seen) return;
                fetch(here + (here.indexOf('?') < 0 ? '?' : '&') + 'from=' + (isList ? 0 : seen),
                      {headers:{'X-Fragment':'1'}})
                  .then(function(r){return r.text()}).then(function(html){
                    if (!html.trim()) return;
                    var y = window.scrollY;
                    if (isList) { document.body.innerHTML = html; window.scrollTo(0, y); }
                    else { document.body.insertAdjacentHTML('beforeend', html);
                           document.body.dataset.events = n.events; }
                  });
              };
            })();
            </script>
            """;

    /** A fold the reader opened stays open across a live update, and so does where they were. */
    private static final String KEEP_OPEN = """
            <script>
            (function(){
              var K='open:'+location.pathname+location.search, S=sessionStorage;
              function all(){return [].slice.call(document.querySelectorAll('details'))}
              try{
                var open=JSON.parse(S.getItem(K)||'[]');
                all().forEach(function(d,i){ if(open.indexOf(i)>=0) d.open=true });
                var y=+S.getItem(K+':y'); if(y) window.scrollTo(0,y);
              }catch(e){}
              document.addEventListener('toggle',function(){
                try{ var open=[]; all().forEach(function(d,i){ if(d.open) open.push(i) });
                     S.setItem(K,JSON.stringify(open)); }catch(e){}
              },true);
              addEventListener('scroll',function(){
                try{S.setItem(K+':y',window.scrollY)}catch(e){}
              },{passive:true});
            })();
            </script>
            """;

    public static void main(String[] args) throws IOException {
        Path results = Path.of(args.length > 0 ? args[0] : "results").toAbsolutePath().normalize();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8086;
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 64);
        // Thread per request: the event stream holds its connection open for the whole poll, so a
        // fixed pool would be exhausted by readers watching rather than by work.
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "dashboard");
            t.setDaemon(true);
            return t;
        }));
        Dashboard d = new Dashboard(results, System.getenv("BJV_DASH_TOKEN"));
        http.createContext("/", d::home);
        http.createContext("/bump", d::bump);
        http.createContext("/feedback", d::feedback);
        http.createContext("/events", d::events);
        http.start();
        System.out.println("dashboard on :" + port + " over " + results
                + (d.token == null ? " (feedback OPEN: set BJV_DASH_TOKEN to require one)"
                : " (feedback requires a token)"));
    }

    private final Path results;
    private final String token;
    /**
     * Measured bumps, keyed by the trace file's size and mtime.
     *
     * <p>A finished bump's trace never changes again, and re-reading every one of them on every
     * page load and every live update is the difference between a dashboard and a second load on
     * the machine doing the actual work. A thousand settled traces is hundreds of megabytes.
     */
    private final Map<String, Facts> measured = new java.util.concurrent.ConcurrentHashMap<>();

    private Dashboard(Path results, String token) {
        this.results = results;
        this.token = token == null || token.isBlank() ? null : token;
    }

    // ---- the fleet ----

    private void home(HttpExchange x) throws IOException {
        // THE QUEUE COMES FIRST. Work that has not started is still work, and a page built only
        // from what has already reported shows a thousand-repo sweep as whatever four repos happen
        // to be in flight, with no denominator and therefore no progress and no ETA.
        Map<String, Map<String, String>> latest = new LinkedHashMap<>();
        for (String line : read(results.resolve("queue.tsv"))) {
            String[] col = line.split("\t");
            if (col.length >= 5) {
                latest.put(col[1] + "|" + col[2] + "|" + col[3] + "|" + col[4],
                        Map.of("state", "queued", "because", ""));
            }
        }
        // A bump's last settlement row is its state; everything else is measured from its trace.
        for (String line : read(results.resolve("settlements.jsonl"))) {
            Map<String, String> r = row(line);
            String bump = r.getOrDefault("bump", "");
            if (!bump.isBlank()) {
                latest.put(bump, r);
            }
        }
        // A bump is IN FLIGHT only while its lane holds a claim. Without this the page counts a
        // crashed bump as running forever, and six "bumping" rows behind four lanes is the page
        // lying about the one number a reader checks first.
        Set<String> claimed = new LinkedHashSet<>();
        try (var c = Files.list(results.resolve("claims"))) {
            c.forEach(f -> claimed.add(f.getFileName().toString()));
        } catch (IOException none) {
            // No claims directory yet: nothing is running, or the launcher predates them.
        }
        List<Facts> facts = new ArrayList<>();
        long began = Long.MAX_VALUE;
        int events = 0;
        int minutes = 0;
        for (Map.Entry<String, Map<String, String>> e : latest.entrySet()) {
            Facts f = measure(e.getKey(), e.getValue());
            if (f.state.equals("bumping") && !claimed.isEmpty() && !claimed.contains(f.slug)) {
                f = f.as("interrupted");
            }
            facts.add(f);
            events += f.events;
            minutes += f.minutes;
            if (f.first > 0) {
                began = Math.min(began, f.first);
            }
        }
        int total = facts.size();
        int settled = (int) facts.stream().filter(f -> !f.state.equals("bumping")
                && !f.state.equals("queued") && !f.state.equals("interrupted")).count();
        long elapsed = began == Long.MAX_VALUE ? 0 : System.currentTimeMillis() - began;

        StringBuilder b = head("bumps", total + " bump(s) · " + events + " trace event(s) · as of "
                + when(String.valueOf(System.currentTimeMillis())) + "Z");
        if (total == 0) {
            send(x, b.append("<div class=empty>Nothing has run yet.</div>").toString());
            return;
        }
        b.append("<script>document.body.dataset.settled=")
                .append(read(results.resolve("settlements.jsonl")).size())
                .append(";document.body.dataset.at=Date.now()</script>");
        b.append(progress(total, settled, elapsed, minutes, facts));
        b.append("<table><tr><th>bump</th><th>hop</th><th>state</th><th>tests</th>"
                + "<th>target</th><th>cve</th><th>walls</th><th>human-equiv</th><th>took</th>"
                + "<th>latest</th></tr>");
        // Active first, then finished newest-first, then the queue in its own order: a reader
        // opens this to see what is happening, not to page through what has not begun.
        List<Facts> active = facts.stream().filter(f -> f.state.equals("bumping")
                || f.state.equals("interrupted")).toList();
        List<Facts> done = new ArrayList<>(facts.stream()
                .filter(f -> !f.state.equals("bumping") && !f.state.equals("queued")
                        && !f.state.equals("interrupted")).toList());
        java.util.Collections.reverse(done);
        List<Facts> queued = facts.stream().filter(f -> f.state.equals("queued")).toList();
        active.forEach(f -> b.append(f.row()));
        done.forEach(f -> b.append(f.row()));
        queued.forEach(f -> b.append(f.row()));
        send(x, b.append("</table>").toString());
    }

    /**
     * The bar, how far in, how long it has taken and how long is left.
     *
     * <p>The ETA is settled bumps over elapsed time, extrapolated. It is honest only while the
     * bumps are alike, which they are not: one the surveyor declines costs a minute and one that
     * grinds eight gate turns on a hundred-module reactor costs hours. It is shown because an
     * estimate that converges beats none, and labelled so nobody plans around it.
     */
    private static String progress(int total, int settled, long elapsed, int minutes,
                                   List<Facts> facts) {
        int pct = Math.min(100, settled * 100 / Math.max(1, total));
        String eta = settled > 0 && settled < total
                ? clock(elapsed / settled * (total - settled)) : "—";
        StringBuilder b = new StringBuilder("<div class=bar><i style='width:" + pct
                + "%'></i></div><div class=counts>");
        b.append(tile(settled + " / " + total, pct + "% settled"))
                .append(tile(clock(elapsed), "elapsed"))
                .append(tile(eta, "eta, extrapolated"))
                .append(tile(clock(minutes * 60_000L), "human-equivalent"));
        // Vulnerabilities only over the bumps where BOTH scans exist, since an after-count without
        // a green gate is not a measurement and averaging it in would flatter the total.
        List<Facts> scored = facts.stream()
                .filter(f -> f.cveBefore >= 0 && f.cveAfter >= 0).toList();
        if (!scored.isEmpty()) {
            int sumBefore = scored.stream().mapToInt(f -> f.cveBefore).sum();
            int sumAfter = scored.stream().mapToInt(f -> f.cveAfter).sum();
            int removedPct = sumBefore == 0 ? 0 : (sumBefore - sumAfter) * 100 / sumBefore;
            b.append(tile(sumBefore + " \u2192 " + sumAfter, "CRITICAL+HIGH, " + scored.size()
                    + " scored")).append(tile(removedPct + "%", "vulnerabilities removed"));
        }
        // One tile per state actually present: a fixed list of every possible verdict would be
        // mostly zeroes, and a zero tile reads as a category that matters.
        Map<String, Integer> byState = new LinkedHashMap<>();
        facts.forEach(f -> byState.merge(f.state, 1, Integer::sum));
        b.append("</div><div class=counts>");
        byState.forEach((s, n) -> b.append("<div class=c><b>").append(n)
                .append("</b><span class='s ").append(esc(s)).append("'>").append(esc(s))
                .append("</span></div>"));
        b.append("</div>");
        // HOW FAR THE FLEET GETS. A state histogram says what bumps became; this says where they
        // stop, which is the question a run is actually being watched to answer.
        b.append("<div class=funnel>");
        for (int i = 0; i < CHAIN.length; i++) {
            final int stage = i;
            long n = facts.stream().filter(f -> f.reached >= stage).count();
            b.append("<div class=fstep><b>").append(n).append("</b>")
                    .append(esc(CHAIN[i][0])).append("</div>");
        }
        return b.append("</div>").toString();
    }

    private static String tile(String big, String label) {
        return "<div class=c><b>" + esc(big) + "</b><span>" + esc(label) + "</span></div>";
    }

    /** Everything the index shows about one bump, measured once from its trace. */
    private final class Facts {
        final String bump;
        final String slug;
        final String state;
        final String latest;
        String hop = "";
        int events;
        int minutes;
        long first;
        long last;
        String stamp = "";
        String pre = "";
        String lost = "";
        /** What the FIRST gate turn lost, so a row can show the loop closing, not only where it stopped. */
        String lostFirst = "";
        int turns;
        int cveBefore = -1;
        int cveAfter = -1;
        String target = "";
        String required = "";
        final Set<String> walls = new LinkedHashSet<>();
        Boolean baselineGreen;
        Boolean conserved;
        Boolean targetLanded;
        /** The furthest stage of CHAIN this bump reached, by index; -1 for a queued one. */
        int reached = -1;

        Facts(String bump, Map<String, String> settlement) {
            this.bump = bump;
            this.slug = slug(bump);
            this.state = settlement.getOrDefault("state", "bumping");
            this.latest = settlement.getOrDefault("because", "");
        }

        /** The same measurement under a state the FILESYSTEM established, not the trace. */
        Facts as(String newState) {
            Facts c = new Facts(bump, Map.of("state", newState, "because", latest));
            c.stamp = stamp;
            c.hop = hop;
            c.events = events;
            c.minutes = minutes;
            c.first = first;
            c.last = last;
            c.pre = pre;
            c.lost = lost;
            c.target = target;
            c.required = required;
            c.walls.addAll(walls);
            c.cveBefore = cveBefore;
            c.cveAfter = cveAfter;
            c.lostFirst = lostFirst;
            c.turns = turns;
            c.baselineGreen = baselineGreen;
            c.conserved = conserved;
            c.targetLanded = targetLanded;
            c.reached = reached;
            return c;
        }

        String row() {
            String[] parts = bump.split("\\|");
            String repo = parts.length > 0 ? parts[0] : bump;
            String sha = parts.length > 1 ? parts[1] : "";
            // A loop that halved its losses and a loop that never moved read identically when only
            // the last turn is shown, and that difference is the whole point of having a loop:
            // 47 lost then 23 lost is the reflect loop working, even where it did not win.
            String trend = turns > 1
                    ? "<div class=k>turn " + turns
                    + (lostFirst.isBlank() || lostFirst.equals(lost) ? "" : ", was " + lostFirst)
                    + "</div>"
                    : "";
            String tests = pre.isBlank() ? "—"
                    : (lost.isBlank() ? pre + " passing"
                    : ("0".equals(lost) ? pre + " conserved" : lost + " of " + pre + " lost"));
            // 17 / 17 on a FAILED row is not a contradiction: the target leg passed and another leg
            // did not. Colouring the leg stops the number reading as the verdict for the whole bump.
            String tgt;
            if (target.isBlank()) {
                tgt = "<span class=k>—</span>";
            } else if (required.isBlank()) {
                tgt = esc(target);
            } else {
                boolean landed = num(target) >= num(required);
                tgt = "<span class='t-" + (landed ? "ok" : "low") + "'>" + esc(target) + " / "
                        + esc(required) + "</span>"
                        + (landed ? "" : "<div class=k>not landed</div>");
            }
            return "<tr><td><a href=\"bump?slug=" + url(slug) + "&amp;key=" + url(bump) + "\">"
                    + esc(repo) + "</a><div class=k>" + esc(sha.length() > 8
                    ? sha.substring(0, 8) : sha) + "</div></td>"
                    + "<td class=hop>" + esc(hop.isBlank() ? "—" : hop) + "</td>"
                    + "<td><span class='s " + esc(state) + "'>" + esc(state) + "</span>"
                    + sema() + "</td>"
                    + "<td>" + esc(tests) + trend + "</td>"
                    + "<td>" + tgt + "</td>"
                    + "<td>" + cves() + "</td>"
                    + "<td class=k>" + esc(walls.isEmpty() ? "—" : String.join(", ", walls))
                    + "</td>"
                    + "<td>" + (minutes > 0 ? esc(clock(minutes * 60_000L)) : "—") + "</td>"
                    + "<td>" + esc(clock(last - first)) + "<div class=k>" + events
                    + " event(s)" + quiet() + "</div></td>"
                    + "<td class=latest>" + esc(latest) + "</td></tr>";
        }

        /**
         * How long a running bump has been silent. A lane grinding a large model call looks
         * exactly like a lane that has hung, and the difference is how long it has been quiet.
         */
        String quiet() {
            if (!state.equals("bumping") || last <= 0) {
                return "";
            }
            long since = System.currentTimeMillis() - last;
            return since < 120_000 ? "" : "<br>quiet " + clock(since);
        }

        /**
         * CRITICAL+HIGH before and after, and the direction between them.
         *
         * <p>An after-count exists only where the gate went green, because on a failed bump the
         * count falls when modules stop resolving and that is indistinguishable from a fix.
         */
        String cves() {
            if (cveBefore < 0) {
                return "<span class=k>—</span>";
            }
            if (cveAfter < 0) {
                return "<b>" + cveBefore + "</b><div class=k>before</div>";
            }
            String dir = cveAfter < cveBefore ? "down" : cveAfter > cveBefore ? "up" : "flat";
            return "<span class=cve-" + dir + "><b>" + cveBefore + "</b> \u2192 <b>" + cveAfter
                    + "</b></span><div class=k>" + (cveBefore - cveAfter) + " cleared</div>";
        }

        /** The gate's three legs, in order: a baseline, tests conserved, the target landed. */
        String sema() {
            return "<div class=sema>" + light(baselineGreen) + light(conserved)
                    + light(targetLanded) + "</div>";
        }

        private String light(Boolean v) {
            return "<i class=" + (v == null ? "none" : v ? "green" : "red") + "></i>";
        }
    }

    private static final Pattern GATE = Pattern.compile(
            "pre=(\\d+) lost=(\\d+) effective-target=(-?\\d+)");
    private static final Pattern JDK_TO = Pattern.compile("under JDK (\\d+)");
    private static final Pattern HOP = Pattern.compile("JDK (\\d+) -> (\\d+)|hop: (\\d+)->(\\d+)");

    /** One pass over a bump's trace, remembered while the file is unchanged. */
    private Facts measure(String bump, Map<String, String> settlement) {
        Path trace = results.resolve(slug(bump)).resolve("trace.jsonl");
        String stamp = settlement.getOrDefault("state", "") + ":";
        try {
            stamp += Files.isRegularFile(trace)
                    ? Files.size(trace) + ":" + Files.getLastModifiedTime(trace).toMillis() : "0";
        } catch (IOException e) {
            stamp += "?";
        }
        Facts hit = measured.get(bump);
        if (hit != null && hit.stamp.equals(stamp)) {
            return hit;
        }
        Facts f = read(bump, settlement);
        f.stamp = stamp;
        measured.put(bump, f);
        return f;
    }

    private Facts read(String bump, Map<String, String> settlement) {
        Facts f = new Facts(bump, settlement);
        String[] parts = bump.split("\\|");
        if (parts.length >= 4) {
            f.hop = parts[2] + "→" + parts[3];
            f.required = parts[3];
        }
        for (String line : read(results.resolve(f.slug).resolve("trace.jsonl"))) {
            Map<String, String> r = row(line);
            f.events++;
            long at = num(r.get("at"));
            if (at > 0) {
                if (f.first == 0) {
                    f.first = at;
                }
                f.last = at;
            }
            switch (r.getOrDefault("kind", "")) {
                case "priced" -> f.minutes += (int) num(r.get("minutes"));
                case "applied" -> {
                    String stage = r.getOrDefault("stage", "");
                    String what = r.getOrDefault("what", "");
                    if (stage.equals("walls")) {
                        // The row label up to its first colon: the family, not the exact versions.
                        f.walls.add(what.contains(":") ? what.substring(0, what.indexOf(':')) : what);
                    } else if (stage.equals("baseline")) {
                        Matcher m = Pattern.compile(": (\\d+)$").matcher(what.strip());
                        if (m.find()) {
                            f.pre = m.group(1);
                        }
                    } else if (stage.equals("security-before") || stage.equals("security-after")) {
                        Matcher m = Pattern.compile("CRITICAL\\+HIGH (\\d+)").matcher(what);
                        if (m.find()) {
                            int n = Integer.parseInt(m.group(1));
                            if (stage.endsWith("before")) {
                                f.cveBefore = n;
                            } else {
                                f.cveAfter = n;
                            }
                        }
                    } else if (stage.equals("gate")) {
                        Matcher m = GATE.matcher(what);
                        if (m.find()) {
                            f.turns++;
                            if (f.lostFirst.isBlank()) {
                                f.lostFirst = m.group(2);
                            }
                            f.pre = m.group(1);
                            f.lost = m.group(2);
                            f.target = m.group(3);
                            f.conserved = "0".equals(m.group(2));
                            f.targetLanded = !f.required.isBlank()
                                    && num(m.group(3)) >= num(f.required);
                        }
                    }
                }
                case "built" -> {
                    if ("baseline-test".equals(r.get("phase"))) {
                        f.baselineGreen = "false".equals(r.get("infra"))
                                && "true".equals(r.get("passed"));
                    }
                }
                case "progress" -> {
                    Matcher m = JDK_TO.matcher(r.getOrDefault("note", ""));
                    if (f.hop.isBlank() && m.find()) {
                        f.hop = m.group(1);
                    }
                }
                case "asked" -> {
                    String who = r.getOrDefault("agent", "");
                    for (int i = 0; i < CHAIN.length; i++) {
                        if (CHAIN[i][1].equals(who) || CHAIN[i][2].equals(who)) {
                            f.reached = Math.max(f.reached, i);
                        }
                    }
                    if ("surveyor".equals(r.get("agent"))) {
                        Matcher m = HOP.matcher(r.getOrDefault("reply", ""));
                        if (m.find() && m.group(3) != null) {
                            f.hop = m.group(3) + "→" + m.group(4);
                            f.required = m.group(4);
                        }
                    }
                }
                default -> {
                }
            }
        }
        if ("PASS".equals(f.state)) {
            f.conserved = Boolean.TRUE;
            f.targetLanded = Boolean.TRUE;
            f.baselineGreen = Boolean.TRUE;
        }
        return f;
    }

    // ---- one bump ----

    private void bump(HttpExchange x) throws IOException {
        Map<String, String> q = query(x);
        String slug = q.getOrDefault("slug", "");
        String key = q.getOrDefault("key", slug);
        String only = q.getOrDefault("agent", "");
        int from = (int) num(q.get("from"));
        boolean fragment = x.getRequestHeaders().getFirst("X-Fragment") != null;

        if (!SLUG.matcher(slug).matches()) {
            send(x, head("bump", "").append("<div class=empty>not a bump key</div>").toString());
            return;
        }
        Path f = results.resolve(slug).resolve("trace.jsonl").normalize();
        if (!f.startsWith(results)) {
            // Unreachable given the allowlist, and checked anyway: the allowlist is one edit away
            // from being loosened, and this is the check that still holds when it is.
            send(x, head("bump", "").append("<div class=empty>not a bump key</div>").toString());
            return;
        }
        List<String> lines = read(f);

        StringBuilder b = new StringBuilder();
        if (!fragment) {
            b = head(esc(key), lines.size() + " event(s)");
            b.append("<a class=back href=\".\">&larr; bumps</a>");
            b.append(pipeline(slug, key, lines, only));
            b.append(dependencies(lines));
        }
        int shown = 0;
        int asked = 0;
        // A thought carries no agent name of its own: it is emitted at the model, before the
        // runtime attributes the answer. The agent it belongs to is whoever was mid-call, which is
        // the next 'asked' — so a thought inherits the agent of the reply that follows it.
        Map<Integer, String> owner = new LinkedHashMap<>();
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Map<String, String> r = row(lines.get(i));
            String k = r.getOrDefault("kind", "");
            if (k.equals("thought")) {
                pending.add(i);
            } else if (k.equals("asked")) {
                for (int p : pending) {
                    owner.put(p, r.getOrDefault("agent", ""));
                }
                pending.clear();
            }
        }
        int index = -1;
        for (String line : lines) {
            index++;
            Map<String, String> r = row(line);
            String kind = r.getOrDefault("kind", "");
            if (kind.equals("thought")) {
                r.put("agent", owner.getOrDefault(index, ""));
            }
            boolean isAsked = kind.equals("asked");
            int myIndex = isAsked ? asked++ : -1;
            shown++;
            if (shown <= from) {
                continue;
            }
            if (!only.isBlank() && !only.equals(r.get("agent"))) {
                continue;
            }
            b.append(event(r, kind, key, myIndex));
        }
        if (!fragment) {
            b.append("<script>document.body.dataset.events=").append(lines.size())
                    .append("</script>");
        }
        send(x, b.toString());
    }

    /**
     * The whole chain as a strip: every stage, in order, whether or not it ran.
     *
     * <p>A producer counted more than once is a FEEDBACK LOOP made visible: the chain re-asks only
     * when its critic objected, so "preparer 2" means the prepare-critic sent it back. That is the
     * single most informative number on the page and it is invisible in a plain tab list.
     */
    private String pipeline(String slug, String key, List<String> lines, String only) {
        Map<String, Integer> spoke = new LinkedHashMap<>();
        for (String line : lines) {
            Map<String, String> r = row(line);
            if ("asked".equals(r.get("kind"))) {
                spoke.merge(r.getOrDefault("agent", "?"), 1, Integer::sum);
            }
        }
        StringBuilder b = new StringBuilder("<div class=pipe>");
        b.append("<a class='allpill").append(only.isBlank() ? " on" : "").append("' href=\"bump?slug=")
                .append(url(slug)).append("&amp;key=").append(url(key)).append("\">everything</a>");
        for (String[] stage : CHAIN) {
            int producer = spoke.getOrDefault(stage[1], 0);
            int critic = spoke.getOrDefault(stage[2], 0);
            boolean reached = producer > 0 || critic > 0;
            b.append("<div class='stage").append(reached ? "" : " dim").append("'>")
                    .append("<div class=stagename>").append(esc(stage[0])).append("</div>")
                    .append(agentPill(slug, key, stage[1], producer, only));
            // The loop between them, drawn only when it actually turned.
            b.append("<span class='link").append(producer > 1 ? " looped" : "").append("'>")
                    .append(producer > 1 ? "\u21ba" : "\u2192").append("</span>")
                    .append(agentPill(slug, key, stage[2], critic, only))
                    .append("</div>");
        }
        return b.append("</div>").toString();
    }

    private String agentPill(String slug, String key, String agent, int n, String only) {
        String cls = "ag" + (agent.equals(only) ? " on" : "") + (n == 0 ? " never" : "");
        if (n == 0) {
            return "<span class='" + cls + "'>" + esc(agent) + "</span>";
        }
        return "<a class='" + cls + "' href=\"bump?slug=" + url(slug) + "&amp;key=" + url(key)
                + "&amp;agent=" + url(agent) + "\">" + esc(agent) + " <b>" + n + "</b></a>";
    }

    /**
     * Every resolved dependency, before against after.
     *
     * <p>The count answers whether the bump moved the number; this answers WHAT it moved, which is
     * the question that follows immediately and could not be asked from a total. A row shows the
     * version each scan resolved and the CVEs each carried, and the ones that CHANGED sort to the
     * top, because on most bumps the answer is that almost nothing changed and the exceptions are
     * the whole story.
     */
    private String dependencies(List<String> lines) {
        Map<String, String[]> before = new LinkedHashMap<>();
        Map<String, String[]> after = new LinkedHashMap<>();
        for (String line : lines) {
            Map<String, String> r = row(line);
            if (!"applied".equals(r.get("kind"))) {
                continue;
            }
            String stage = r.getOrDefault("stage", "");
            Map<String, String[]> into = stage.equals("packages-before") ? before
                    : stage.equals("packages-after") ? after : null;
            if (into == null) {
                continue;
            }
            for (String row : r.getOrDefault("what", "").split("\n")) {
                String[] col = row.split("\t");
                if (col.length >= 4) {
                    into.put(col[0] + "|" + col[1], new String[]{col[2], col[3]});
                }
            }
        }
        if (before.isEmpty() && after.isEmpty()) {
            return "";
        }
        Set<String> all = new LinkedHashSet<>(before.keySet());
        all.addAll(after.keySet());
        record Row(String name, String vb, String va, int cb, int ca, boolean moved) {
        }
        List<Row> rows = new ArrayList<>();
        int cveB = 0;
        int cveA = 0;
        for (String k : all) {
            String[] b = before.get(k);
            String[] a = after.get(k);
            String vb = b == null ? "" : b[0];
            String va = a == null ? "" : a[0];
            int cb = b == null ? 0 : (int) num(b[1]);
            int ca = a == null ? 0 : (int) num(a[1]);
            cveB += cb;
            cveA += ca;
            rows.add(new Row(k.substring(k.indexOf('|') + 1), vb, va, cb, ca,
                    !vb.equals(va) || cb != ca));
        }
        // Changed first, then the worst offenders, then alphabetical: a reader is looking for a
        // difference, and failing that for what dominates the count.
        rows.sort((x, y) -> x.moved() != y.moved() ? Boolean.compare(y.moved(), x.moved())
                : Math.max(y.cb(), y.ca()) != Math.max(x.cb(), x.ca())
                ? Integer.compare(Math.max(y.cb(), y.ca()), Math.max(x.cb(), x.ca()))
                : x.name().compareTo(y.name()));
        long moved = rows.stream().filter(Row::moved).count();
        StringBuilder b = new StringBuilder("<details class=deps");
        b.append(moved > 0 ? " open" : "").append("><summary>dependencies: ").append(rows.size())
                .append(" resolved, ").append(moved).append(" changed, CRITICAL+HIGH ")
                .append(cveB).append(" &rarr; ").append(cveA).append("</summary>")
                .append("<table><tr><th>package</th><th>before</th><th>after</th>")
                .append("<th>cve before</th><th>cve after</th></tr>");
        for (Row r : rows) {
            String cls = !r.moved() ? "" : r.va().isEmpty() ? "gone"
                    : r.vb().isEmpty() ? "added" : "moved";
            b.append("<tr class='dep ").append(cls).append("'><td>").append(esc(r.name()))
                    .append("</td><td>").append(esc(r.vb().isEmpty() ? "—" : r.vb()))
                    .append("</td><td>").append(esc(r.va().isEmpty() ? "—" : r.va()))
                    .append("</td><td>").append(r.cb() == 0 ? "" : String.valueOf(r.cb()))
                    .append("</td><td class='").append(r.ca() < r.cb() ? "cve-down"
                            : r.ca() > r.cb() ? "cve-up" : "").append("'>")
                    .append(r.ca() == 0 ? "" : String.valueOf(r.ca())).append("</td></tr>");
        }
        return b.append("</table></details>").toString();
    }

    private String event(Map<String, String> r, String kind, String key, int askedIndex) {
        String when = when(r.get("at"));
        StringBuilder b = new StringBuilder("<div class='ev " + esc(kind) + "'>");
        switch (kind) {
            case "asked" -> {
                b.append("<span class=who>").append(esc(r.get("agent")))
                        .append("</span><span class=kind>asked</span> <span class=k>").append(when)
                        .append("</span>")
                        .append(fold("prompt", r.get("prompt")))
                        .append("<pre>").append(esc(r.get("reply"))).append("</pre>")
                        .append(rate(key, r.get("agent"), askedIndex, r.get("prompt"),
                                r.get("reply")));
            }
            case "thought" -> {
                // The reasoning behind the answer that follows it, and why the answer ended.
                // "length" is the one that matters: an answer cut off mid-thought, which is how a
                // critic ends up saying nothing at all.
                String finish = r.getOrDefault("finish", "");
                b.append("<span class=who>thinking</span><span class=kind>")
                        .append(esc(finish.isBlank() ? "" : "finish " + finish))
                        .append("</span> <span class=k>").append(when).append("</span>")
                        .append("LENGTH".equalsIgnoreCase(finish)
                                ? "<div class=cut>cut off mid-thought: the answer below is what "
                                + "survived the token budget</div>" : "")
                        .append(fold("reasoning", r.get("thinking")));
            }
            case "tool" -> b.append("<span class=who>").append(esc(r.get("agent")))
                    .append("</span><span class=kind>").append(esc(r.get("tool")))
                    .append("</span> <span class=k>").append(when).append("</span>")
                    .append("<pre>").append(esc(abbreviate(r.get("arguments"), 400))).append("</pre>")
                    .append(fold("result", r.get("result")));
            case "built" -> b.append("<span class=who>").append(esc(r.get("phase")))
                    .append("</span><span class=kind>built</span> <span class=k>").append(when)
                    .append("</span><div class=k>infra=").append(esc(r.get("infra")))
                    .append(" passed=").append(esc(r.get("passed"))).append("</div>")
                    .append(fold("build output", r.get("summary")));
            case "applied" -> b.append("<span class=who>").append(esc(r.get("stage")))
                    .append("</span><span class=kind>applied</span> <span class=k>").append(when)
                    .append("</span>").append(fold("what changed", r.get("what")));
            case "settled" -> b.append("<span class=who>").append(esc(r.get("state")))
                    .append("</span><span class=kind>settled</span> <span class=k>").append(when)
                    .append("</span><pre>").append(esc(r.get("because"))).append("</pre>");
            case "priced" -> b.append("<span class=who>").append(esc(r.get("minutes")))
                    .append(" minutes</span><span class=kind>priced</span> <span class=k>")
                    .append(when).append("</span><pre>").append(esc(r.get("itemisation")))
                    .append("</pre>");
            case "failed" -> b.append("<span class=who>").append(esc(r.get("cause")))
                    .append("</span><span class=kind>failed</span> <span class=k>").append(when)
                    .append("</span>").append(fold("stack", r.get("stack")));
            case "progress" -> b.append("<span class=k>").append(when).append(" — ")
                    .append(esc(r.get("note"))).append("</span>");
            case "system" -> b.append("<span class=kind>system prompt</span>")
                    .append(fold("prompt", r.get("prompt")));
            default -> b.append("<span class=kind>").append(esc(kind)).append("</span>");
        }
        return b.append("</div>").toString();
    }

    /** The form carries the pair itself, so a filed row is a self-contained training example. */
    private String rate(String key, String agent, int index, String prompt, String reply) {
        if (index < 0) {
            return "";
        }
        return "<form class=rate method=post action=feedback>"
                + (token == null ? "" : hidden("token", token))
                + hidden("bump", key) + hidden("agent", agent)
                + hidden("event", String.valueOf(index))
                + hidden("prompt", prompt) + hidden("reply", reply)
                + "<input name=note placeholder=\"what was wrong or right about this reply\">"
                + " <button>file feedback</button></form>";
    }

    private static String fold(String label, String body) {
        return body == null || body.isEmpty() ? ""
                : "<details><summary>" + esc(label) + " (" + body.length() + " chars)</summary>"
                + "<pre>" + esc(body) + "</pre></details>";
    }

    // ---- the live stream ----

    /**
     * Push when either file grows, and a comment otherwise so the proxy does not reap an idle
     * connection. Line counts, not contents: the page asks for what it is missing itself.
     */
    private void events(HttpExchange x) throws IOException {
        x.getResponseHeaders().add("Content-Type", "text/event-stream");
        x.getResponseHeaders().add("Cache-Control", "no-cache");
        x.sendResponseHeaders(200, 0);
        try (var out = x.getResponseBody()) {
            int last = -1;
            int lastSettled = -1;
            // Long enough that a reader who leaves the page open does not watch it silently stop
            // updating. The browser reconnects when this ends, but a gap in a live view reads as a
            // stalled run, which is the impression the whole page exists to avoid.
            for (int tick = 0; tick < 10_800; tick++) {
                int now = count();
                int settled = read(results.resolve("settlements.jsonl")).size();
                // TWO COUNTERS, because the two pages have different appetites. A bump page wants
                // every event; the index wants only state changes, since re-rendering a thousand
                // rows on each of a thousand tool calls is a denial of service we would be
                // committing against ourselves.
                String msg = (now != last || settled != lastSettled)
                        ? "data: {\"events\":" + now + ",\"settled\":" + settled + "}\n\n"
                        : ": ping\n\n";
                last = now;
                lastSettled = settled;
                out.write(msg.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(2000);
            }
        } catch (IOException | InterruptedException gone) {
            // The reader closed the tab, or the run ended. Either way there is nothing to say.
        }
    }

    private int count() {
        int n = read(results.resolve("settlements.jsonl")).size();
        try (var dirs = Files.list(results)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                n += read(d.resolve("trace.jsonl")).size();
            }
        } catch (IOException none) {
            // A results directory that cannot be listed is an empty one for this purpose.
        }
        return n;
    }

    // ---- the one write ----

    private void feedback(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) {
            x.sendResponseHeaders(405, -1);
            return;
        }
        byte[] body = x.getRequestBody().readNBytes(MAX_FEEDBACK_BYTES + 1);
        if (body.length > MAX_FEEDBACK_BYTES) {
            x.sendResponseHeaders(413, -1);
            return;
        }
        Map<String, String> form = form(new String(body, StandardCharsets.UTF_8));
        if (token != null && !token.equals(form.get("token"))) {
            // A feedback row is a training example. An unauthenticated append is a way to write
            // into the corpus that tunes the prompts, which is worth more than the disk it costs.
            x.sendResponseHeaders(403, -1);
            return;
        }
        new Feedback(form.getOrDefault("bump", ""), form.getOrDefault("agent", ""),
                (int) num(form.get("event")), form.getOrDefault("note", ""),
                Instant.now().toString(), form.getOrDefault("prompt", ""),
                form.getOrDefault("reply", ""))
                .appendTo(results.resolve("feedback").resolve("feedback.jsonl"));
        String bump = form.getOrDefault("bump", "");
        x.getResponseHeaders().add("Location", "bump?slug=" + url(slug(bump))
                + "&key=" + url(bump));
        x.sendResponseHeaders(303, -1);
    }

    // ---- small helpers; the flat-string JSON these files use needs no library to read back ----

    static String slug(String bump) {
        return bump.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private static List<String> read(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readAllLines(p) : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Milliseconds as something a person reads: 4m 28s, 14h 23m. */
    static String clock(long ms) {
        if (ms <= 0) {
            return "—";
        }
        long s = ms / 1000;
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return (s / 60) + "m " + (s % 60) + "s";
        }
        return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
    }

    private static long num(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Tolerant of both quoted strings and bare numbers/booleans; the two files differ on that. */
    static Map<String, String> row(String jsonl) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        while (i < jsonl.length() - 1) {
            int k1 = jsonl.indexOf('"', i);
            int k2 = k1 < 0 ? -1 : jsonl.indexOf('"', k1 + 1);
            if (k2 < 0) {
                break;
            }
            String key = jsonl.substring(k1 + 1, k2);
            int colon = jsonl.indexOf(':', k2);
            if (colon < 0) {
                break;
            }
            int scan = colon + 1;
            while (scan < jsonl.length() && jsonl.charAt(scan) == ' ') {
                scan++;
            }
            if (scan < jsonl.length() && jsonl.charAt(scan) == '"') {
                StringBuilder v = new StringBuilder();
                int p = scan + 1;
                while (p < jsonl.length()) {
                    char ch = jsonl.charAt(p);
                    if (ch == '\\' && p + 1 < jsonl.length()) {
                        char n = jsonl.charAt(++p);
                        switch (n) {
                            case 'n' -> v.append('\n');
                            case 't' -> v.append('\t');
                            case 'r' -> v.append('\r');
                            case 'u' -> {
                                v.append((char) Integer.parseInt(jsonl, p + 1, p + 5, 16));
                                p += 4;
                            }
                            default -> v.append(n);
                        }
                    } else if (ch == '"') {
                        break;
                    } else {
                        v.append(ch);
                    }
                    p++;
                }
                out.put(key, v.toString());
                i = p + 1;
            } else {
                int stop = scan;
                while (stop < jsonl.length() && ",}".indexOf(jsonl.charAt(stop)) < 0) {
                    stop++;
                }
                out.put(key, jsonl.substring(scan, stop).trim());
                i = stop + 1;
            }
        }
        return out;
    }

    private static Map<String, String> query(HttpExchange x) {
        return form(x.getRequestURI().getRawQuery() == null ? "" : x.getRequestURI().getRawQuery());
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                try {
                    out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
                } catch (IllegalArgumentException malformed) {
                    // A parameter that is not valid encoding is not a parameter.
                }
            }
        }
        return out;
    }

    private static StringBuilder head(String title, String sub) {
        return new StringBuilder("<!doctype html><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>").append(esc(title)).append("</title><style>").append(CSS)
                .append("</style>").append(LIVE).append(KEEP_OPEN)
                .append("<header><h1>").append(esc(title)).append("</h1><div class=sub>")
                .append(esc(sub)).append("</div></header>");
    }

    private static String hidden(String name, String value) {
        return "<input type=hidden name=" + name + " value=\"" + esc(value) + "\">";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + " … (" + s.length() + " chars)";
    }

    private static String when(String millis) {
        try {
            return Instant.ofEpochMilli(Long.parseLong(millis)).toString().replace("T", " ")
                    .substring(0, 19);
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Everything rendered here is untrusted: model output, and source from third-party
     * repositories. Both quote characters are escaped so the output is safe in either attribute
     * style, not only the one a given line happens to use today.
     */
    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String url(String s) {
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange x, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        x.sendResponseHeaders(200, bytes.length);
        try (var out = x.getResponseBody()) {
            out.write(bytes);
        }
    }
}
