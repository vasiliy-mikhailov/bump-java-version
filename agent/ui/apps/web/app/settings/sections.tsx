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
  /** "this page" or "the environment", in the words the pill beside it says them in. */
  keySource: string
  /** The file under the run root, named and not pathed: the reader's shell is on the host. */
  storedIn: string
  /** When it was saved here, or 0 when the key is the environment's. */
  storedAt: number
  /** True when the key on screen is not the one this dashboard process was started with. */
  differsFromLaunch: boolean
  saved: boolean
  /** Why a save was refused, in words that do not repeat what was typed. */
  why: string
  model: string
  endpoint: string
  patienceMinutes: string
}

/**
 * THE ENDPOINT, AND THE KEY WITH IT, WHICH REVERSES WHAT THIS SECTION USED TO SAY.
 *
 * WHAT STOOD HERE IS KEPT, because a reader needs to know the trade was considered and overridden
 * rather than never made. It read: the sibling tool renders its API key here, with the reveal and
 * copy buttons that cannot work otherwise, and its own mount contract flags that as the part a
 * shell author must read twice: defensible for one person behind their own proxy, not on a portal
 * several developers reach. This tool is the second one mounted, so it takes the other side of that
 * trade. Whether a key is SET travels; the key never does. There is no reveal button because there
 * is nothing behind it.
 *
 * THE OWNER REVERSED IT KNOWING THE KEY WOULD THEN TRAVEL to every browser that opens this page.
 * What protects it is one password, the basic_auth in front of the whole zone. The Java records the
 * same decision at more length, including where the key is stored and why it cannot be stored
 * anywhere better.
 *
 * WHAT THIS PAGE MUST NOT DO IS IMPLY THE SAVE REACHED THE SWEEP. It does not: `run.sh` reads the
 * key once at startup and hands it to every lane it opens, so a running sweep keeps its launcher's
 * key no matter what is stored here. That sentence is on the card rather than in this comment,
 * because the reader who needs it is the one pressing save.
 */
export function ModelSection() {
  const [model, setModel] = useState<Model | null>(null)
  const [typed, setTyped] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [said, setSaid] = useState<string | undefined>(undefined)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    read<Model>('/api/settings/model')
      .then(setModel)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  const save = (key: string) => {
    setBusy(true)
    setSaid(undefined)
    read<Model>('/api/settings/model', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ key }),
    })
      .then((r) => {
        setModel(r)
        // WHAT THE SERVER KEPT, AND ONLY WHEN IT KEPT SOMETHING. A refused save leaves the box as
        // it was typed, because the reader is about to correct it and retyping a key from a
        // password manager to fix one character is how a second wrong key gets saved.
        if (r.saved) {
          setTyped(null)
        }
        setSaid(r.saved ? 'stored, and the sweep already running is unchanged' : r.why)
      })
      .catch((e: Error) => setSaid(e.message))
      .finally(() => setBusy(false))
  }

  return (
    <Loaded what="model" failed={failed} value={model}>
      {(model) => (
        <SettingCard
          title="the endpoint"
          provenance={model.keySource === '' ? 'no key' : `the key is ${model.keySource}'s`}
          footnote={
            <>
              The key is readable and editable here, with the reveal and copy buttons that cannot work
              otherwise. This page used to refuse to show it, on the grounds that a portal several
              developers reach should not render a credential to all of them; that was reversed
              deliberately, and what now protects the key is the one password in front of this zone.
              Rotating that password is no longer the same act as rotating the key.
              <br />
              The model, the endpoint and the patience below are the environment's and are shown only.
              A page that could redirect the endpoint could point every agent at a machine of its own
              choosing.
            </>
          }
        >
          {/* WHAT THE ABSENCE MEANS IS THIS PAGE'S SENTENCE AND NOT THE COMPONENT'S, and this page
              now has the sibling's answer rather than its old one: there is a box here, so the
              sentence says what to do with it. */}
          <KeyStatus
            keyed={model.keySet}
            keySource={model.keySource}
            whenAbsent="every agent call is refused before it is made, until a key is saved here or set on the container"
          />
          <Account>
            A sweep already running keeps the key its launcher started with until that launcher
            restarts. run.sh reads the key once, at startup, and hands it to each lane as it opens
            one, so what is saved here is what the next launch reads and has no effect on the lanes
            running now. This has already been observed the other way round: the key was changed in
            the environment and every lane in flight carried on with the previous one.
            {model.differsFromLaunch
              ? ' Right now the key in the box is not the one this dashboard was started with, so nothing at all is using it yet.'
              : ''}
          </Account>
          <SecretField
            label="API key"
            value={typed ?? model.key}
            onChange={setTyped}
            hint={
              <>
                Stored under the run root as {model.storedIn}, readable by nobody but the user the
                sweep runs as. A blank or malformed save is refused rather than stored: a settings
                page that can empty the key is a settings page that can stop the next sweep in
                silence. To go back to the environment&rsquo;s key, delete that file with a shell.
                {model.storedAt === 0 ? null : (
                  <>
                    {' '}
                    Saved <RelativeTime at={model.storedAt} />.
                  </>
                )}
              </>
            }
          />
          <SaveRow onSave={() => save(typed ?? model.key)} busy={busy} said={said} />
          {/* WHAT A SAVE DOES NOT PROVE, said once rather than left to be assumed. The metering
              proxy is the thing that would know whether this key is a real one and which lane it
              bills, and it publishes its labels while keeping the mapping from key to label inside
              its own process. There is no cheap question this server can ask it, so the page claims
              nothing about it instead of inventing a check. */}
          <Account>
            Saving stores the key; it does not test it. The inference proxy knows which label a key
            meters as, but it publishes only the labels and never the mapping, and this container
            cannot read another container&rsquo;s environment. A wrong key shows up as the next
            launch&rsquo;s lanes being refused, not here.
          </Account>
          <div style={{ height: '14px' }} />
          <LabeledField label="model" hint="What to ask for. Must be a name the endpoint below serves.">
            <input style={READONLY} value={model.model} readOnly />
          </LabeledField>
          <LabeledField
            label="endpoint"
            hint="OpenAI-shaped, ending in /v1. The scheme decides the protocol."
          >
            <input style={READONLY} value={model.endpoint} readOnly />
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
