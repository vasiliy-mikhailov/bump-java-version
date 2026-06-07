import json, os, time, urllib.request, re
from datetime import datetime, timezone

# observability compactor (frog's eye, P10) endpoint/model/key from .env -> Qwen3.6-27B-AWQ via gateway /awq route
_OBSENV = {}
for _l in open("/home/vmihaylov/java_8_11_17_to_java_21/.env"):
    _l = _l.strip()
    if _l and not _l.startswith("#") and "=" in _l:
        _k, _v = _l.split("=", 1); _OBSENV[_k] = _v.strip().strip('"').strip("'")
OBS_URL = _OBSENV.get("OBSERVABILITY_COMPACTOR_BASE_URL", "https://inference.mikhailov.tech/qwen-3.6-27b-awq/v1").rstrip("/")
OBS_MODEL = _OBSENV.get("OBSERVABILITY_COMPACTOR_MODEL", "qwen3.6-27b-awq")
OBS_KEY = _OBSENV.get("OBSERVABILITY_COMPACTOR_API_KEY") or _OBSENV.get("PROPOSER_API_KEY", "")

OBS_DIR = "/var/log/observe"
DIGEST = f"{OBS_DIR}/digest.jsonl"
CTX_BUDGET = 64 * 1024   # MoE (Qwen3.6-35B-A3B-AWQ) max-model-len = 65536
OUTPUT_BUDGET = 4000   # triage output is small (labels+kinds only); keeps generation well under the client timeout
SAFETY = 2000  # system prompt + wrappers
COMPACT_AT = int(CTX_BUDGET * 0.40)   # trigger compaction at 40% of budget
HARD_CAP = CTX_BUDGET - OUTPUT_BUDGET - SAFETY  # never send more than this
RECENT_KEEP = 8
DEEP_REVIEW_S = 90
TAIL_INTERVAL_S = 5
MAX_INGEST = 5000   # cap lines ingested per stream per tick so a huge backlog can't OOM the buffer

STREAMS = {"host": f"{OBS_DIR}/host_metrics.jsonl",
           "docker": f"{OBS_DIR}/docker.jsonl",
           "app": f"{OBS_DIR}/app_logs.jsonl"}

COMPACT_SYSTEM = (
    "You are an observability triage model. You receive a prior summary plus a list of pre-collapsed "
    "event groups. Each group has: \"i\" (index), \"s\" (stream), \"n\" (occurrences), \"distinct\" "
    "(distinct values), \"span\" ([first,last] time), and \"sample\" (a line of the actual text). "
    "Your ONLY job is to TRIAGE: pick the notable / anomalous groups and give each a short human label and a kind. "
    "Do NOT echo counts, times, or the verbatim text — those are kept exactly as given and re-attached by index. "
    "Return JSON ONLY: {\"summary\":\"<one or two sentences on overall host state>\","
    "\"picks\":[{\"i\":<group index int>,\"what\":\"<short human label, e.g. 'SSH brute force on root'>\","
    "\"kind\":\"<one of: error|security|resource|build|network|churn|info>\"}]} "
    "Pick at most the ~25 most notable groups (rank by severity, then by n/distinct); fold the rest into the summary. "
    "A high n or distinct count is itself a signal. JSON only, no prose.")


def approx_tokens(t): return (len(t) + 1) // 2  # conservative upper bound


def _extract_json(content):
    """Pull the first valid JSON object out of a model reply. Uses a real JSON
    parser (raw_decode) so braces inside quoted strings — e.g. Vector's
    'source{component_kind=...}' log lines in verbatim 'last' fields — don't
    break extraction at any nesting depth. Tolerates ```json fences / prose."""
    s = (content or "").strip()
    if s.startswith("```"):
        body = s[3:]
        if body[:4].lower().startswith("json"):
            body = body[4:]
        end = body.rfind("```")
        s = (body[:end] if end != -1 else body).strip()
    dec = json.JSONDecoder()
    start = s.find("{")
    while start != -1:
        try:
            obj, _ = dec.raw_decode(s, start)
            return obj
        except json.JSONDecodeError:
            start = s.find("{", start + 1)
    return None


def ask_qwen(system, user, max_tokens=OUTPUT_BUDGET):
    body = {"model":OBS_MODEL,"messages":[
            {"role":"system","content":system},{"role":"user","content":user}],
            "temperature":0.0,"max_tokens":max_tokens,"chat_template_kwargs":{"enable_thinking":False}}
    req = urllib.request.Request(OBS_URL+"/chat/completions",
        data=json.dumps(body).encode(),
        headers={"Authorization":"Bearer "+OBS_KEY,"Content-Type":"application/json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=240) as r:
                content = (json.loads(r.read())["choices"][0]["message"].get("content") or "").strip()
            obj = _extract_json(content)
            return obj if obj is not None else {"raw": content[:300]}
        except Exception as e:
            if attempt == 2: return {"err": str(e)}
            time.sleep(2 ** attempt)


buffer = []
offsets = {}
compacted_summary = None
last_deep = time.time()
last_alarm = ""


def init_offsets_at_end():
    """Skip Vector's backlog: start reading from current end of each file."""
    for stream, path in STREAMS.items():
        try:
            sz = os.path.getsize(path)
            offsets[stream] = sz
        except Exception:
            offsets[stream] = 0
    print(f"init offsets: {offsets}", flush=True)


OFFSETS_FILE = "/home/vmihaylov/.compactor.offsets"  # outside /var/log so Vector doesn't tail it


def save_offsets():
    """Persist offsets after each successful compaction so a restart resumes where it
    left off (never drops the backlog). Saved offsets reflect condensed data only."""
    try:
        tmp = OFFSETS_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(offsets, f)
        os.replace(tmp, OFFSETS_FILE)
    except Exception:
        pass


def load_or_init_offsets():
    """Resume from persisted offsets (process the backlog); only fall back to
    end-of-file on a true cold start with no saved offsets."""
    try:
        with open(OFFSETS_FILE) as f:
            saved = json.load(f)
        for stream in STREAMS:
            offsets[stream] = int(saved.get(stream, 0))
        print(f"resumed offsets from {OFFSETS_FILE}: {offsets}", flush=True)
    except Exception:
        init_offsets_at_end()


def tail_new_lines():
    for stream, path in STREAMS.items():
        if not os.path.exists(path): continue
        try:
            new = []
            with open(path) as f:
                f.seek(offsets[stream])
                for _ in range(MAX_INGEST):   # bounded: don't slurp a huge backlog into memory at once
                    line = f.readline()
                    if not line: break
                    new.append(line)
                offsets[stream] = f.tell()
            for line in new:
                line = line.strip()
                if not line: continue
                try: ev = json.loads(line)
                except: continue
                t = ev.get("timestamp") or ev.get("@timestamp") or datetime.now(timezone.utc).isoformat()
                if stream == "host":
                    compact_ev = {"name": ev.get("name","?"),
                                  "val": (ev.get("gauge",{}) or ev.get("counter",{}) or {}).get("value"),
                                  "tags": ev.get("tags",{})}
                elif stream == "docker":
                    compact_ev = {"container": ev.get("container_name"),
                                  "msg": (ev.get("message") or "")[:4000]}
                else:
                    compact_ev = {"file": (ev.get("file","") or "").split("/")[-1],
                                  "msg": (ev.get("message") or "")[:4000]}
                buffer.append({"t": t, "s": stream, "e": compact_ev})
        except Exception: pass


def per_sample_alarm():
    global last_alarm
    if len(buffer) < 5: return
    recent = buffer[-50:]
    errs = [b for b in recent if b["s"] == "app" and re.search(r"\b(error|exception|traceback)\b", b["e"].get("msg",""), re.I)]
    if not errs: return
    # Dedupe by (file, first_60_chars)
    sigs = sorted({(b["e"].get("file"), b["e"].get("msg","")[:60]) for b in errs})
    sig_str = " | ".join(f"{f}: {m[:50]}" for f,m in sigs[:3])
    alarm = f"{len(errs)} error events from {len(sigs)} sources — {sig_str}"
    if alarm == last_alarm: return
    last_alarm = alarm
    entry = {"t": datetime.now(timezone.utc).isoformat(), "kind": "alarm", "facts": alarm}
    with open(DIGEST, "a") as f: f.write(json.dumps(entry) + "\n")
    print(f"ALARM: {alarm[:200]}", flush=True)


def _serialize(buf): return "\n".join(json.dumps(b) for b in buf)


def chunk_buffer(buf, chunk_token_cap):
    """Split buffer into time-ordered chunks whose serialization fits chunk_token_cap each."""
    chunks, cur, cur_tok = [], [], 0
    for b in buf:
        t = approx_tokens(json.dumps(b))
        if cur and cur_tok + t > chunk_token_cap:
            chunks.append(cur); cur, cur_tok = [], 0
        cur.append(b); cur_tok += t
    if cur: chunks.append(cur)
    return chunks


def triage(collapsed, prior_summary):
    """Send the model a SMALL view of each collapsed group (index + count + a sample line)
    and get back labels/kinds only. Exact n/distinct/span/last/params stay in `collapsed`
    and are re-attached by index — so the model never transcribes verbose data (fast) and
    the numbers can't drift (accurate)."""
    view = [{"i": i, "s": g["s"], "n": g["n"], "distinct": g["distinct"],
             "span": g["span"], "sample": (g["last"] or "")[:200]} for i, g in enumerate(collapsed)]
    user = ("(Prior compaction:)\n" + (prior_summary or "(none)") +
            "\n\n(Collapsed event groups:)\n" + "\n".join(json.dumps(x) for x in view))
    resp = ask_qwen(COMPACT_SYSTEM, user)
    summary = resp.get("summary", "") or ""
    anomalies = []
    for p in (resp.get("picks", []) or []):
        if not isinstance(p, dict): continue
        try: g = collapsed[int(p["i"])]
        except (KeyError, ValueError, IndexError, TypeError): continue
        anomalies.append({"what": p.get("what", "?"), "kind": p.get("kind", "info"),
                          "n": g["n"], "distinct": g["distinct"], "span": g["span"],
                          "last": g["last"], "params": g["params"]})
    return summary, anomalies


_NORM = re.compile(r"[0-9a-f]{6,}|\d+|0x[0-9a-f]+")


def _sig(b):
    """Signature for collapsing repetitions: stream + source + message with the
    variable bits (numbers, hex ids, veth names, pids, timestamps) masked out."""
    e = b.get("e", {}) or {}
    msg = e.get("msg") or e.get("name") or ""
    key = str(e.get("file") or e.get("container") or "").rsplit("/", 1)[-1]
    return (b.get("s"), key, _NORM.sub("#", str(msg))[:100])


def collapse(buf):
    """Collapse repeats into one group that carries the count AND the variations behind
    the masked signature + the time span — so the model can say e.g. 'SSH auth failures
    n=100 from 3 IPs (a,b,c), span 11:20-13:05', not just 'n=100'. This is the ~100-500x
    reduction (repetition summarized, not re-sent) while keeping the distinct variants."""
    groups = {}
    for b in buf:
        s = _sig(b)
        e = b.get("e", {}) or {}
        msg = e.get("msg") or e.get("name") or json.dumps(e)
        t = (b.get("t") or "")[:19]
        v = msg[:200]  # the distinct value within the group (e.g. the specific IP / path / iface)
        g = groups.get(s)
        if g is None:
            groups[s] = {"n": 1, "s": b.get("s"), "span": [t, t], "last": msg,
                         "vars": {v: [1, t, t]}}  # value -> [count, first_seen, last_seen]
        else:
            g["n"] += 1
            if t and t < g["span"][0]: g["span"][0] = t
            if t and t >= g["span"][1]: g["span"][1] = t
            g["last"] = msg  # buffer is time-ordered, so the final occurrence is the most recent
            pv = g["vars"].get(v)
            if pv is not None:
                pv[0] += 1
                if t and t < pv[1]: pv[1] = t
                if t and t >= pv[2]: pv[2] = t
            elif len(g["vars"]) < 256:
                g["vars"][v] = [1, t, t]
    out = []
    for g in groups.values():
        # per-variant breakdown: the busiest distinct values, each with its own count + time window
        top = sorted(g["vars"].items(), key=lambda kv: -kv[1][0])[:12]
        params = [{"what": val, "n": cnt, "when": [first, last]} for val, (cnt, first, last) in top]
        out.append({"n": g["n"], "s": g["s"], "span": g["span"],
                    "distinct": len(g["vars"]), "last": g["last"], "params": params})
    return out


MAX_GROUPS = 350   # cap groups shown to the model (busiest first) so triage input stays small/fast


def compact():
    global buffer, compacted_summary, last_deep
    collapsed = collapse(buffer)  # dedup repetitions -> only distinct/anomalous events reach the model
    collapsed.sort(key=lambda g: -g["n"])           # busiest groups first
    shown = collapsed[:MAX_GROUPS]
    view_tok = approx_tokens("\n".join(json.dumps(
        {"i": i, "n": g["n"], "sample": (g["last"] or "")[:200]}) for i, g in enumerate(shown)))
    print(f"COMPACT samples={len(buffer)} -> {len(collapsed)} distinct (showing {len(shown)}), ~{view_tok} tok view", flush=True)
    new_summary, anomalies = triage(shown, compacted_summary)
    if len(collapsed) > len(shown):
        new_summary = (new_summary + f" (+{len(collapsed)-len(shown)} lower-volume groups omitted)").strip()
    compacted_summary = new_summary
    entry = {"t": datetime.now(timezone.utc).isoformat(), "kind": "compaction",
             "samples_compacted": len(buffer), "distinct_groups": len(collapsed),
             "summary": new_summary, "anomalies": anomalies}
    with open(DIGEST, "a") as f: f.write(json.dumps(entry)+"\n")
    print(f"COMPACT done. distinct={len(collapsed)} anomalies={len(anomalies)}", flush=True)
    buffer = buffer[-RECENT_KEEP:]
    last_deep = time.time()
    save_offsets()


def main():
    print(f"compactor_v3: resume backlog, DEEP_REVIEW={DEEP_REVIEW_S}s, HARD_CAP={HARD_CAP} tok", flush=True)
    load_or_init_offsets()
    while True:
        tail_new_lines()
        if buffer:
            per_sample_alarm()
            buf_text = "\n".join(json.dumps(b) for b in buffer)
            buf_tokens = approx_tokens((compacted_summary or "") + buf_text)
            if (buf_tokens >= COMPACT_AT or (time.time() - last_deep) >= DEEP_REVIEW_S) and len(buffer) >= 20:
                compact()
        time.sleep(TAIL_INTERVAL_S)


if __name__ == "__main__":
    main()
