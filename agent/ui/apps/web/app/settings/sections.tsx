'use client'

import { useEffect, useState } from 'react'
import type { Finding } from '@bjv/types'
import {
  ACCOUNT,
  Account,
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
  keySet: boolean
  model: string
  endpoint: string
  patienceMinutes: string
}

/**
 * THE ENDPOINT, AND DELIBERATELY NOT THE KEY.
 *
 * The sibling tool renders its API key here, with the reveal and copy buttons that cannot work
 * otherwise, and its own mount contract flags that as the part a shell author must read twice:
 * defensible for one person behind their own proxy, not on a portal several developers reach. This
 * tool is the second one mounted, so it takes the other side of that trade. Whether a key is SET
 * travels; the key never does. There is no reveal button because there is nothing behind it.
 */
export function ModelSection() {
  const [model, setModel] = useState<Model | null>(null)
  const [failed, setFailed] = useState<string | null>(null)

  useEffect(() => {
    read<Model>('/api/settings/model')
      .then(setModel)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  return (
    <Loaded what="model" failed={failed} value={model}>
      {(model) => (
        <SettingCard
          title="the endpoint"
          provenance="the environment's"
          footnote={
            <>
              This page does not set any of these, and it never shows the key. The sibling tool does show
              its own, and says in its mount contract why that is a trade rather than an oversight: the
              reveal and copy buttons cannot work otherwise. On a portal several developers reach, a
              settings page that renders a credential publishes it to all of them.
            </>
          }
        >
          {/* WHAT THE ABSENCE MEANS IS THIS PAGE'S SENTENCE AND NOT THE COMPONENT'S. The sibling
              tool's settings page can set a key, so its no-key sentence tells a reader what to do
              next; this one deliberately renders no key field at all, so its sentence has to say
              that the fix is somewhere else entirely. Neither is true of the other page. */}
          <KeyStatus
            keyed={model.keySet}
            keySource="the environment"
            whenAbsent="every agent call will be refused until one is set on the container"
          />
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
      <pre
        style={{
          margin: '0 0 10px',
          padding: '10px 12px',
          overflowX: 'auto',
          borderRadius: '6px',
          background: 'var(--bg-subtle)',
          border: '1px solid var(--border-soft)',
          fontSize: '12px',
          lineHeight: 1.5,
          color: 'var(--text-secondary)',
        }}
      >
        {`url, sha, from, to, key

https://github.com/owner/name, bdc86ebe64e2ec6d…, 11, 17,
https://github.com/owner/other,                 , 17, 21,
https://git.internal/team/thing, 9f1c2d…        , 11, 17, ghp_xxx`}
      </pre>
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
