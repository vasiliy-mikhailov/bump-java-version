'use client'

import { useEffect, useState } from 'react'
import type { Finding } from '@bjv/types'
import {
  ACCOUNT,
  Account,
  CodeBlock,
  Disclosure,
  EmptyNote,
  FIELD,
  ForgetKeyChoice,
  KeyStatus,
  LabeledField,
  Loaded,
  Pill,
  READONLY,
  RelativeTime,
  SaveRow,
  SecretField,
  SettingCard,
} from '@bjv/ui'
import { read } from '@/lib/api'

type Run = {
  lanes: number | null
  min: number
  max: number
  turns: string
  repairBudget: string
  steps: string
  hangGuardMinutes: string
  /** Where dependencies resolve from, and the caches the sandbox mounts to reach them. */
  repository: string
  mavenSettings: string
  mavenCache: string
  gradleCache: string
  gradleDists: string
  offline: boolean
}

/**
 * THE ONE SETTING ON THIS PAGE THAT IS GENUINELY LIVE.
 *
 * `run.sh` re-reads `max_lanes` at the top of every round rather than at launch, so a sweep starving
 * the GPU can be throttled without being stopped. That mechanism already existed and was reachable
 * only by someone with a shell on the box; this is the same lever with a label on it.
 *
 * THE SERVER CLAMPS AND THE PAGE SHOWS WHAT IT KEPT. Echoing the request would show 40 lanes on a
 * box that will run 16, and the reader would not find out until the sweep failed to speed up.
 */
export function RunSection() {
  const [run, setRun] = useState<Run | null>(null)
  const [typed, setTyped] = useState('')
  const [busy, setBusy] = useState(false)
  const [said, setSaid] = useState<string | undefined>(undefined)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    read<Run>('/api/settings/run')
      .then((r) => {
        setRun(r)
        setTyped(String(r.lanes ?? ''))
      })
      .catch((e: Error) => setFailed(e.message))
  }, [])

  const save = () => {
    setBusy(true)
    setSaid(undefined)
    read<Run>('/api/settings/run', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ lanes: Number(typed) }),
    })
      .then((r) => {
        setRun(r)
        setTyped(String(r.lanes ?? ''))
        setSaid(`kept ${r.lanes}`)
      })
      .catch((e: Error) => setSaid(e.message))
      .finally(() => setBusy(false))
  }

  return (
    <Loaded what="run" failed={failed} value={run}>
      {(run) => (
        <>
          <SettingCard
            title="parallel lanes"
            provenance={run.lanes === null ? 'not set' : `currently ${run.lanes}`}
            footnote={
              <>
                Takes effect at the start of the next round, not immediately. Lowering it does not stop a
                bump that is already running — the sweep simply stops replacing finished lanes until it
                is back under the number.
              </>
            }
          >
            <Account>
              How many repositories are bumped at the same time. Between {run.min} and {run.max}. The
              server clamps what you save, so what appears here afterwards is what it kept, not what you
              typed.
            </Account>
            <LabeledField label="lanes">
              <input
                style={FIELD}
                value={typed}
                inputMode="numeric"
                onChange={(e) => setTyped(e.target.value)}
              />
            </LabeledField>
            <SaveRow onSave={save} busy={busy} said={said} />
          </SettingCard>

          <div style={{ height: '18px' }} />

          <SettingCard
            title="the repair budget"
            provenance="the environment's"
            footnote={
              <>
                Set on the container and read at launch, so changing one means a redeploy. Repair happens
                inside the module walk: a module is compiled on its own and repaired until it compiles or
                its turns run out. The repository gate runs once after the walk and does not retry, so
                there is no gate-turn budget any more.
              </>
            }
          >
            <LabeledField
              label="module-gate turns"
              hint="How many times one module may be compiled and repaired before the walk moves on."
            >
              <input style={READONLY} value={run.turns} readOnly />
            </LabeledField>
            <LabeledField
              label="steps per campaign"
              hint="Steps one repair campaign may order, and a campaign may run twice."
            >
              <input style={READONLY} value={run.steps} readOnly />
            </LabeledField>
            {/* THE ONE THAT ACTUALLY BINDS. Turns times steps times campaigns is per module, so on a
                twenty-module repository the per-module numbers alone would allow seven hundred steps.
                This is the whole bump's allowance and the walk draws it down across every module. */}
            <LabeledField
              label="repair steps per bump"
              hint="The whole bump's allowance, shared by every module. This is the ceiling that binds."
            >
              <input style={READONLY} value={run.repairBudget} readOnly />
            </LabeledField>
          </SettingCard>

          <div style={{ height: '18px' }} />

          <SettingCard
            title="where dependencies come from"
            provenance={run.repository === '' ? 'not set' : 'the environment\u2019s'}
            footnote={
              <>
                Builds here resolve offline through a local mirror, so what is not in it does not exist
                as far as a bump is concerned: a version the agent raises to and the mirror has never
                seen fails the build rather than downloading. Until now this was knowable only by
                reading settings.xml on the host. Maven learns the mirror from that file and never
                needed it named anywhere else, but Gradle cannot read a Maven settings file, so the
                moment a recipe has to run under Gradle the URL has to be configuration rather than a
                line inside a file handed to one build tool.
                <br />
                Read-only, like the model endpoint. A repository URL a web page can rewrite is a supply
                chain a web page can redirect.
              </>
            }
          >
            <LabeledField
              label="repository"
              hint="The mirror every build resolves through. BJV_REPO_URL."
            >
              <input
                style={READONLY}
                value={run.repository === '' ? 'not set' : run.repository}
                readOnly
              />
            </LabeledField>
            <LabeledField label="maven settings" hint="Handed to maven; this is where it reads the mirror.">
              <input style={READONLY} value={run.mavenSettings || 'not set'} readOnly />
            </LabeledField>
            <LabeledField label="maven repository" hint="The warm cache mounted at /root/.m2.">
              <input style={READONLY} value={run.mavenCache || 'not set'} readOnly />
            </LabeledField>
            <LabeledField label="gradle cache" hint="Mounted read-only for gradle builds.">
              <input style={READONLY} value={run.gradleCache || 'not set'} readOnly />
            </LabeledField>
            <LabeledField
              label="gradle distributions"
              hint="Which wrapper versions can be staged. A version missing here cannot be downloaded."
            >
              <input style={READONLY} value={run.gradleDists || 'not set'} readOnly />
            </LabeledField>
          </SettingCard>
        </>
      )}
    </Loaded>
  )
}

type Model = {
  /**
   * THE KEY ITSELF, which this payload used not to carry. The Java's `Settings.model` records the
   * decision that put it here and what it costs; everything else about this field, the mask, the
   * reveal, the no-store on the response, follows from it.
   */
  key: string
  keySet: boolean
  /** "this page" or "the environment", or "" when there is no key anywhere to have a source. */
  keySource: string
  /** The file under the run root, named and not pathed: the reader's shell is on the host. */
  storedIn: string
  /** When the key was saved here, or 0 when the key is the environment's. */
  storedAt: number
  /** Whether anything at all is saved here, which is what the card's provenance reports. */
  edited: boolean
  /** True when the key on screen is not the one this dashboard process was started with. */
  differsFromLaunch: boolean
  /** True when the last lane to start read the store as it stands now. A fact about the past. */
  laneHasThis: boolean
  /** When that lane started, or 0 when no lane has recorded which settings it read. */
  laneStartedAt: number
  saved: boolean
  /** Why a save was refused, in words that do not repeat what was typed. */
  why: string
  model: string
  endpoint: string
  patienceMinutes: string
  /** Shown and not settable: ratchet-llm writes both as literals. See the card. */
  temperature: string
  tokenCap: string
}

/**
 * THE ENDPOINT, THE KEY AND THE MODEL, AND THE TWO REVERSALS THAT PUT THEM HERE.
 *
 * WHAT STOOD HERE IS KEPT, because a reader needs to know the trade was considered and overridden
 * rather than never made. It read: the sibling tool renders its API key here, with the reveal and
 * copy buttons that cannot work otherwise, and its own mount contract flags that as the part a
 * shell author must read twice: defensible for one person behind their own proxy, not on a portal
 * several developers reach. This tool is the second one mounted, so it takes the other side of that
 * trade. Whether a key is SET travels; the key never does. It also read that the endpoint stays
 * read-only, because a page that could redirect it could point every agent at a machine of its own
 * choosing.
 *
 * BOTH WERE REVERSED BY THE OWNER, the key first and the endpoint for parity with the sibling. The
 * Java records both at more length, including what the second one costs, which is more than the
 * first: one password now stands between a browser and pointing every future lane at a server of
 * somebody else's choosing, which is then handed the key from this same card.
 *
 * WHAT THIS PAGE USED TO GET WRONG WAS NOT THE POLICY BUT THE CLAIM. It said what is saved here is
 * what the next launch reads, and nothing read it: `model_key` was written by the server beside
 * this card and opened by no launcher, no lane and no supervisor. `run.sh` reads the store once per
 * LANE now, so the sentence is true and stronger, and the two cases where it is still not true, a
 * launcher already inside its loop and the supervisor in this container, are said on the card with
 * the evidence for which one is which.
 */
export function ModelSection() {
  const [model, setModel] = useState<Model | null>(null)
  const [typedKey, setTypedKey] = useState<string | null>(null)
  const [typedModel, setTypedModel] = useState<string | null>(null)
  const [typedEndpoint, setTypedEndpoint] = useState<string | null>(null)
  const [forget, setForget] = useState(false)
  const [busy, setBusy] = useState(false)
  const [said, setSaid] = useState<string | undefined>(undefined)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    read<Model>('/api/settings/model')
      .then(setModel)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  const save = (m: Model) => {
    setBusy(true)
    setSaid(undefined)
    read<Model>('/api/settings/model', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        // ABSENT MEANS LEAVE IT ALONE, AND ONLY THE KEY GETS THAT RULE. A blank key box is not
        // sent at all, so a browser that empties it cannot unset the credential; the checkbox is
        // the only way to drop one. The other two are always sent, because for them an emptied box
        // is the instruction to fall back to the environment's value, and this page has no other
        // way to say it.
        ...((typedKey ?? m.key).trim().length === 0 ? {} : { key: typedKey ?? m.key }),
        model: typedModel ?? m.model,
        endpoint: typedEndpoint ?? m.endpoint,
        ...(forget ? { forget: '1' } : {}),
      }),
    })
      .then((r) => {
        setModel(r)
        // WHAT THE SERVER KEPT, AND ONLY WHEN IT KEPT SOMETHING. A refused save leaves the boxes as
        // they were typed, because the reader is about to correct one character and retyping a key
        // from a password manager to fix it is how a second wrong key gets saved.
        if (r.saved) {
          setTypedKey(null)
          setTypedModel(null)
          setTypedEndpoint(null)
          setForget(false)
        }
        setSaid(r.saved ? 'stored, and read by the next lane that opens' : r.why)
      })
      .catch((e: Error) => setSaid(e.message))
      .finally(() => setBusy(false))
  }

  return (
    <Loaded what="model" failed={failed} value={model}>
      {(model) => (
        <SettingCard
          title="the endpoint"
          provenance={model.edited ? 'edited' : "the environment's"}
          changed={model.edited}
          footnote={
            <>
              Takes effect on the next lane a launcher opens. Nothing running is disturbed: a bump is
              a fresh container per repository, and run.sh reads this store beside the docker run
              that starts one rather than once when the sweep began.
              <br />
              The key is readable and editable here, with the reveal and copy buttons that cannot
              work otherwise, and so is the endpoint. This page used to refuse both, on the grounds
              that a portal several developers reach should not render a credential to all of them
              and that a page which can redirect the endpoint can point every agent at a machine of
              its own choosing. Both were reversed deliberately, and what protects them is the one
              password in front of this zone. Rotating that password is no longer the same act as
              rotating the key.
            </>
          }
        >
          {/* WHAT THE ABSENCE MEANS IS THIS PAGE'S SENTENCE AND NOT THE COMPONENT'S, and this page
              now has the sibling's answer rather than its old one: there is a box here, so the
              sentence says what to do with it. */}
          <KeyStatus
            keyed={model.keySet}
            keySource={model.keySource === '' ? 'the environment' : model.keySource}
            whenAbsent="nothing is saved here and nothing is set on the container, so no lane will open at all until a key is saved here or set on the container"
          />
          <Account>
            Every lane reads this store first and the environment underneath, in that order, and a
            lane with no key anywhere is refused rather than started. That refusal is what lets the
            pill above be read as it is written: a green pill means the next lane is given this key
            or does not open, instead of opening and sending an empty bearer token to an endpoint
            that refuses it while the chain runs on to a verdict out of silence.
            {model.laneHasThis ? (
              <>
                {' '}
                The last lane to start read what is on this page
                {model.laneStartedAt === 0 ? null : (
                  <>
                    {' '}
                    (<RelativeTime at={model.laneStartedAt} />)
                  </>
                )}
                .
              </>
            ) : model.laneStartedAt === 0 ? (
              <>
                {' '}
                No lane has recorded which settings it read, which is what a launcher started before
                this existed looks like. Until one does, nothing here proves a lane has picked this
                up.
              </>
            ) : (
              <>
                {' '}
                No lane has started with this since it was saved. The last one started{' '}
                <RelativeTime at={model.laneStartedAt} /> and read what was here then. A launcher
                already inside its loop is executing the script it was started with, because bash
                reads a script by byte offset, so its lanes carry on with what it read until it
                drains and is restarted.
              </>
            )}
            {model.differsFromLaunch ? (
              <>
                {' '}
                The supervisor sharing this container is a third case again: it built its models
                when the container started, a JVM cannot change its own environment, and the key on
                screen is not the one this process was handed. It keeps that one until the next
                deploy.
              </>
            ) : null}
          </Account>
          <SecretField
            label="API key"
            value={typedKey ?? model.key}
            onChange={setTypedKey}
            hint={
              <>
                This is the key in force, the one every lane is given. Blank leaves it alone rather
                than clearing it, so a browser that empties this box cannot silently unset the
                credential and leave every agent talking to an endpoint that refuses them; use the
                checkbox to drop a key saved here and fall back to the environment&rsquo;s. Stored
                under the run root as {model.storedIn}, readable by nobody but the user the sweep
                runs as.
                {model.storedAt === 0 ? null : (
                  <>
                    {' '}
                    Saved <RelativeTime at={model.storedAt} />.
                  </>
                )}
              </>
            }
          />
          <ForgetKeyChoice keySource={model.keySource} checked={forget} onChange={setForget} />
          <LabeledField
            label="model"
            hint="What to ask for. Must be a name the endpoint below serves. Emptying this box falls back to the environment's, which is how an override is undone without a shell."
          >
            <input
              style={FIELD}
              value={typedModel ?? model.model}
              onChange={(e) => setTypedModel(e.currentTarget.value)}
              spellCheck={false}
            />
          </LabeledField>
          <LabeledField
            label="endpoint"
            hint="OpenAI-shaped, ending in /v1. The scheme decides the protocol: https negotiates HTTP/2, anything else stays on 1.1, because offering h2c on a cleartext endpoint gets it accepted by vLLM which then loses the body. Emptying this box falls back to the environment's. This field redirects the fleet: every lane opened after a save is pointed at whatever is typed here and is handed the key above."
          >
            <input
              style={FIELD}
              value={typedEndpoint ?? model.endpoint}
              onChange={(e) => setTypedEndpoint(e.currentTarget.value)}
              spellCheck={false}
            />
          </LabeledField>
          <SaveRow onSave={() => save(model)} busy={busy} said={said} />
          {/* WHAT A SAVE DOES NOT PROVE, said once rather than left to be assumed. The metering
              proxy is the thing that would know whether this key is a real one and which lane it
              bills, and it publishes its labels while keeping the mapping from key to label inside
              its own process. There is no cheap question this server can ask it, so the page claims
              nothing about it instead of inventing a check. */}
          <Account>
            Saving stores the values; it does not test them. The inference proxy knows which label a
            key meters as, but it publishes only the labels and never the mapping, and this container
            cannot read another container&rsquo;s environment. A wrong key or a wrong endpoint shows
            up as the next lane being refused, not here.
          </Account>
          <div style={{ height: '14px' }} />
          <LabeledField
            label="temperature"
            // SHOWN AND NOT SETTABLE, AND THE REASON IS ON THE PAGE RATHER THAN IN A COMMIT. The
            // sibling's card sets this one. Ours would be a box that changes nothing, which is the
            // exact defect the rest of this card was rewritten to remove.
            hint="Zero, because these agents certify: a judge that answers differently on the same evidence twice is not a judge. Not settable here, and that is a limit rather than a policy: ratchet-llm builds every client with this written in, so moving it needs a ratchet-llm release and a rebuilt image, not a form."
          >
            <input style={READONLY} value={model.temperature} readOnly />
          </LabeledField>
          <LabeledField
            label="token cap"
            hint="How much of one answer to allow. Not settable here for the same reason as the temperature above: it is a constant inside ratchet-llm. The bound that is tunable from the outside is the thinking budget, which bounds the reasoning rather than the reply and is set on the container."
          >
            <input style={READONLY} value={model.tokenCap} readOnly />
          </LabeledField>
          <LabeledField
            label="patience"
            // THE INDENTATION OF THE TWO LINES BELOW IS PART OF THE STRING, not part of the
            // layout. A quoted JSX attribute is taken verbatim, newlines and leading spaces
            // included, so re-indenting this block rewrites the sentence a reader is served.
            // It survived exactly that once already, and the only thing that caught it was a
            // byte comparison of the rendered page. Left at the column it was written at.
            hint="Minutes one call may take before the harness stops waiting. Generous on purpose: a
             reasoning model that derails can take two hours, and cutting the budget short turns a
             rare stall into a common one."
          >
            <input style={READONLY} value={`${model.patienceMinutes} minutes`} readOnly />
          </LabeledField>
        </SettingCard>
      )}
    </Loaded>
  )
}

type Subject = { queued: number; settled: number; hops: Record<string, number> }

type Uploaded = {
  accepted: number
  added: number
  /** How many rows carried a credential. The COUNT: no response ever carries a key's value. */
  keyed: number
  /** How many left their sha blank, and so will be pinned to whatever the default branch is now. */
  unpinned: number
  sweepLive: boolean
  target: string
  rejected: { line: number; text: string; why: string }[]
}

/**
 * UPLOAD A REGISTRY, AND BE TOLD EXACTLY WHAT IT DID.
 *
 * The file is read here and posted as text, so the same path serves the button and the paste box
 * and no multipart parser has to exist on the server.
 *
 * EVERY LINE THAT WOULD NOT PARSE COMES BACK, with its number and the reason. A loader that answers
 * "ok" to a file half of which it discarded gives back a number smaller than the file and no way to
 * find out which rows are missing, which is worse than refusing the file outright.
 */
function RegistryUpload({ onLoaded }: { onLoaded: () => void }) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<Uploaded | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  const send = (body: string) => {
    if (body.trim() === '') return
    setBusy(true)
    setFailed(null)
    read<Uploaded>('/api/settings/registry', {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain' },
      body,
    })
      .then((r) => {
        setResult(r)
        onLoaded()
      })
      .catch((e: Error) => setFailed(e.message))
      .finally(() => setBusy(false))
  }

  return (
    <SettingCard
      title="load a registry"
      {...(result === null ? {} : { provenance: `${result.added} added` })}
      footnote={
        <>
          Rows merge into the manifest the sweep is reading and are picked up at the start of the
          next round; the round in flight finishes on the file it opened. A repository already in the
          corpus at the same starting level keeps the commit it was queued at — an upload adds work,
          it does not re-point work already counted.
        </>
      }
    >
      {/* THE ONE PARAGRAPH ON THIS PAGE THAT IS NOT AN `Account`, said out loud rather than made
          into a third prop: it leads straight into the block below it and sits closer to it than a
          paragraph that stands on its own. */}
      <p style={{ ...ACCOUNT, margin: '0 0 10px' }}>
        One bump per line, five comma-separated fields:
      </p>
      {/* THE INLINE COPY THAT PROVED THE POINT, NOW THE COMPONENT. Eight of these declarations were
          identical to the `CodeBlock` this repository already had, and the ninth was missing: the
          monospace stack the component carried and this copy had dropped, so the sample rendered in
          whatever face the browser gives a bare `<pre>`. An unused component drifting from its own
          inline duplicate is the evidence that decided which version of `CodeBlock` became the
          shared one, and this call site is the drift. It now reads the shared component, which
          carries the stack, so the sample changes face here on most machines. That is deliberate.

          THE MARGIN IS IN A WRAPPER BECAUSE THE COMPONENT HAS NONE. Three margins existed for one
          box across the two repositories, so the shared shell sets `margin: 0` and the call site
          says what it wants, the way `HEADING` already ships. `0 0 10px` is what this page had. */}
      <div style={{ margin: '0 0 10px' }}>
        <CodeBlock
          code={`url, sha, from, to, key

https://github.com/owner/name, bdc86ebe64e2ec6d…, 11, 17,
https://github.com/owner/other,                 , 17, 21,
https://git.internal/team/thing, 9f1c2d…        , 11, 17, ghp_xxx`}
        />
      </div>
      <Account>
        <strong>Every comma is mandatory, including the last one.</strong> The sha and the key may be
        blank, but they may not be missing: a row with a comma dropped is a row whose columns cannot
        be told apart, and guessing which optional was left out is how a key gets read as a JDK
        level.
      </Account>
      <Account>
        A blank sha takes the default branch and records the commit it resolved, so a re-run still
        measures the same tree. A branch name is refused for the opposite reason. A key is used to
        clone and is stored where this page cannot read it back — the reply below says how many rows
        carried one and never what they were. A line that will not parse is reported back with the
        others rather than dropped.
      </Account>

      <div style={{ display: 'flex', alignItems: 'center', gap: '12px', margin: '0 0 12px' }}>
        <input
          type="file"
          accept=".tsv,.txt,text/plain,text/tab-separated-values"
          disabled={busy}
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file !== undefined) {
              file.text().then(send)
            }
          }}
          style={{ font: 'inherit', fontSize: '12px' }}
        />
      </div>

      <Disclosure summary="paste a list instead">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={6}
          placeholder={'https://github.com/owner/name, bdc86ebe…, 11, 17,'}
          style={{ ...FIELD, maxWidth: '100%', fontFamily: 'inherit', resize: 'vertical' }}
        />
        <div style={{ marginTop: '8px' }}>
          <SaveRow onSave={() => send(text)} busy={busy} />
        </div>
      </Disclosure>

      {failed !== null ? (
        <p style={{ marginTop: '12px', fontSize: '12.5px', color: 'var(--cve-introduced)' }}>
          The upload failed: {failed}
        </p>
      ) : null}

      {result === null ? null : (
        <div style={{ marginTop: '14px' }}>
          <p style={{ margin: '0 0 8px', fontSize: '12.5px' }}>
            {result.accepted} row(s) parsed, {result.added} new, into{' '}
            <code>{result.target}</code>.{' '}
            {result.keyed > 0 ? `${result.keyed} carried a key. ` : ''}
            {result.unpinned > 0
              ? `${result.unpinned} had no sha and will be pinned when the lane clones them. `
              : ''}
            {result.sweepLive
              ? 'A sweep is running; the new rows join the next round.'
              : 'No sweep is running, so this waits for the next launch.'}
          </p>
          {result.rejected.length === 0 ? (
            <Pill tone="good">every line parsed</Pill>
          ) : (
            <>
              <Pill tone="warn">{result.rejected.length} line(s) not loaded</Pill>
              <ul style={{ listStyle: 'none', margin: '8px 0 0', padding: 0 }}>
                {result.rejected.map((r) => (
                  <li key={r.line} style={{ padding: '5px 0', fontSize: '12px' }}>
                    <span style={{ color: 'var(--text-tertiary)' }}>line {r.line}</span>{' '}
                    <span style={{ color: 'var(--cve-introduced)' }}>{r.why}</span>
                    <div
                      style={{
                        color: 'var(--text-tertiary)',
                        overflowX: 'auto',
                        whiteSpace: 'pre',
                      }}
                    >
                      {r.text}
                    </div>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </SettingCard>
  )
}

/** What the sweep is working through. The sibling's subject is a marker; this one's is a queue. */
export function SubjectSection() {
  const [subject, setSubject] = useState<Subject | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  // Remounted by its parent on a successful upload, which is simpler than a refetch signal and
  // cannot leave the card showing a count from before the rows it just accepted.
  useEffect(() => {
    read<Subject>('/api/settings/subject')
      .then(setSubject)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  return (
    <Loaded what="queue" failed={failed} value={subject}>
      {(subject) => {
        const hops = Object.entries(subject.hops)
        return (
          <SettingCard
            title="the queue"
            provenance="the manifest's"
            footnote={
              <>
                The hop is the experiment&rsquo;s independent variable: it arrives in the manifest row and
                nothing in the chain may change it. A surveyor that disagrees is recorded as disagreeing
                and the prescribed hop is run anyway.
              </>
            }
          >
            <div style={{ display: 'flex', gap: '26px', flexWrap: 'wrap', margin: '0 0 14px' }}>
              <LabeledField label="rows in the queue">
                <input style={READONLY} value={String(subject.queued)} readOnly />
              </LabeledField>
              <LabeledField label="bumps with a record">
                <input style={READONLY} value={String(subject.settled)} readOnly />
              </LabeledField>
            </div>
            {hops.length === 0 ? (
              <EmptyNote>No queue file; this deployment is not running a sweep.</EmptyNote>
            ) : (
              <table style={{ borderCollapse: 'collapse', fontSize: '12.5px' }}>
                <tbody>
                  {hops.map(([hop, n]) => (
                    <tr key={hop}>
                      <td style={{ padding: '3px 24px 3px 0' }}>{hop}</td>
                      <td style={{ padding: '3px 0', color: 'var(--text-tertiary)' }}>{n}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SettingCard>
        )
      }}
    </Loaded>
  )
}

/** The queue, and the way to add to it. */
export function SubjectPanel() {
  const [reload, setReload] = useState(0)
  return (
    <>
      <SubjectSection key={reload} />
      <div style={{ height: '18px' }} />
      <RegistryUpload onLoaded={() => setReload((n) => n + 1)} />
    </>
  )
}

/**
 * `latest` used to be that same shape written out inline, which is `Finding` under another name and
 * was the only reason `Finding` looked unused. A grep for it found no importers; a grep for its
 * fields found this.
 */
type Supervisor = {
  everyMinutes: string
  findings: number
  postponed: number
  latest: Finding[]
}

/** Not a setting: a thing that watches the run. Which is why it sits apart in the tab bar. */
export function SupervisorSection() {
  const [sup, setSup] = useState<Supervisor | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    read<Supervisor>('/api/settings/supervisor')
      .then(setSup)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  return (
    <Loaded what="supervisor" failed={failed} value={sup}>
      {(sup) => (
        <SettingCard
          title="the watch"
          provenance={`every ${sup.everyMinutes} minutes`}
          footnote={
            <>
              It reads every bump&rsquo;s trace and looks for what one bump cannot see about itself: a
              lane that has stopped moving, a failure shape repeating across repositories. It may
              postpone a bump; it never edits one.
            </>
          }
        >
          <div style={{ display: 'flex', gap: '26px', flexWrap: 'wrap', margin: '0 0 14px' }}>
            <LabeledField label="findings">
              <input style={READONLY} value={String(sup.findings)} readOnly />
            </LabeledField>
            <LabeledField label="postponed">
              <input style={READONLY} value={String(sup.postponed)} readOnly />
            </LabeledField>
          </div>
          {sup.latest.length === 0 ? (
            <EmptyNote>Nothing found yet. On a healthy sweep that is the expected answer.</EmptyNote>
          ) : (
            <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {sup.latest
                .slice()
                .reverse()
                .map((f, i) => (
                  <li
                    key={`${f.at}-${i}`}
                    style={{
                      padding: '8px 0',
                      borderTop: i === 0 ? 'none' : '1px solid var(--border-soft)',
                      fontSize: '12.5px',
                    }}
                  >
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'baseline' }}>
                      <Pill tone={f.held ? 'warn' : 'quiet'}>{f.held ? 'held' : 'noted'}</Pill>
                      <span style={{ color: 'var(--text-tertiary)' }}>{f.bump}</span>
                      <span style={{ marginLeft: 'auto', color: 'var(--text-tertiary)' }}>
                        <RelativeTime at={f.at} />
                      </span>
                    </div>
                    <div style={{ marginTop: '3px', color: 'var(--text-secondary)' }}>{f.what}</div>
                  </li>
                ))}
            </ul>
          )}
        </SettingCard>
      )}
    </Loaded>
  )
}
