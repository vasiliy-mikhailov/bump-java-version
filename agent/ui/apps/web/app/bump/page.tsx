'use client'

import { Suspense, useEffect, useRef, useState } from 'react'
import type { BumpDetail } from '@bjv/types'
import {
  Card,
  CORNER_BUSY,
  CORNER_BUTTON,
  CORNER_MARK,
  CORNER_REFUSED,
  ChainStrip,
  EmptyNote,
  EventFeed,
  HEADER_NOTE,
  Loaded,
  PackageTable,
  PageHeader,
  RoundHistory,
  SecurityDelta,
  Section,
  SetAsideButton,
  SetAsideNote,
  TabRow,
  useAsk,
  VerdictPill,
} from '@bjv/ui'
import type { TraceEvent } from '@bjv/types'
import { href, live, post, read } from '@/lib/api'
import { Nav } from '../nav'

/** Which half of the page is showing. The record is everything that happened, in order. */
type Tab = 'summary' | 'record'

/**
 * ONE BUMP: the chain it walked, what it did, and what it cost.
 *
 * The slug, the tab and the agent filter live in the query string rather than the path because the
 * page is statically exported — one HTML file serves every bump, and a path segment would need one
 * file per bump generated at build, of a set that grows while the sweep runs.
 *
 * THE CHAIN SITS ABOVE THE TABS, not inside one, because it is the answer to "how far did this
 * get" and that question does not belong to either half. The sibling keeps its strip in the same
 * place for the same reason.
 */
function BumpPage() {
  const [detail, setDetail] = useState<BumpDetail | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [slug, setSlug] = useState('')
  const [only, setOnly] = useState<string | undefined>(undefined)
  const [tab, setTab] = useState<Tab>('summary')
  const [held, setHeld] = useState<Held>({ said: null, why: '' })
  const said = useRef<HTMLSpanElement>(null)

  /**
   * Ask for this bump again.
   *
   * A REFUSAL IS A 200. The server answers {queued:false,why} when there is nothing settled under
   * the slug, so what reaches the hook's catch is the network and the page being served without its
   * api, and the two have to read differently or a wrong slug looks like an outage.
   */
  const again = useAsk<void, Requeue>({
    send: () => post<Requeue>(`/api/rerun?slug=${encodeURIComponent(slug)}`),
    read: (r) => ({ landed: r.queued, why: r.why }),
    // The bump IS queued now, so the pill says so rather than going on showing a verdict that is
    // about to be replaced. Which also takes the control away, and should: a second ask would be a
    // second row in the manifest for a bump already waiting for a lane.
    onAnswer: (r) => {
      if (r.queued) {
        setDetail((d) => (d === null ? d : { ...d, summary: { ...d.summary, verdict: 'queued' } }))
      }
    },
  })

  /**
   * Set this bump aside, or bring it back. One machine, because the two are one toggle.
   *
   * A REFUSAL IS A 200 HERE TOO, and it is recognised off the STATE rather than off the presence of
   * an `error`: the answer carries what is on disk now, so an ask that did not move it is a refusal
   * whether or not a reason came with it.
   *
   * EVERY ANSWER UPDATES WHAT THE PAGE BELIEVES, refusals included, because the answer is read back
   * off disk rather than echoed from the request. It is the state the launcher will see.
   */
  const hold = useAsk<boolean, Postponement>({
    send: (aside) =>
      post<Postponement>(
        `/api/postpone?slug=${encodeURIComponent(slug)}&state=${aside ? 'on' : 'off'}`,
      ),
    read: (r, aside) => ({ landed: r.postponed === aside, why: r.error }),
    onAnswer: (r) => setHeld({ said: r.postponed, why: r.why }),
  })

  useEffect(() => {
    const q = new URLSearchParams(window.location.search)
    const s = q.get('slug') ?? ''
    const agent = q.get('agent')
    setSlug(s)
    setOnly(agent ?? undefined)
    // AN AGENT FILTER IS A REQUEST FOR THE RECORD. Landing on the summary having asked what one
    // agent did would answer a question nobody asked and hide the one they did.
    setTab(agent !== null || q.get('tab') === 'record' ? 'record' : 'summary')
    read<BumpDetail>(`/api/bump?slug=${encodeURIComponent(s)}`)
      .then(setDetail)
      .catch((e: Error) => setFailed(e.message))
  }, [])

  // THE RECORD GROWS WHILE YOU READ IT. The trace is append-only, so the stream sends what was
  // added and the page puts it on the end. Nothing is refetched: a bump that has been running an
  // hour is thousands of events, and asking for all of them again to learn about one is the
  // problem this replaces.
  useEffect(() => {
    // SUBSCRIBE ONLY ONCE THE HISTORY IS IN, and tell the server how much of it we hold. The trace
    // is append-only, so a line count is a stable place to resume: start from zero and every event
    // already on screen arrives again, start from the end and anything written between the fetch
    // and the subscription is lost.
    if (slug === '' || detail === null) {
      return undefined
    }
    return live(`/api/live?slug=${encodeURIComponent(slug)}&have=${detail.events.length}`, {
      trace: (e) =>
        setDetail((known) =>
          known === null ? known : { ...known, events: [...known.events, e as TraceEvent] }),
    })
    // Deliberately not depending on `detail`: it changes with every event that arrives, and
    // resubscribing on each one would reopen the stream forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slug, detail !== null])

  // WHICH STATE THE TOGGLE IS ALREADY IN. A bump that is set aside looks otherwise exactly like one
  // waiting for a lane, and a control that cannot say which of the two it is looking at is a coin
  // flip. There is no per-bump field carrying it, so the answer comes from the directory listing
  // that is the state: one request, once, for a page about one bump.
  useEffect(() => {
    if (slug === '') {
      return
    }
    read<Postponements>('/api/postponed')
      .then((r) =>
        // AN ANSWER THAT ARRIVES AFTER A CLICK MUST NOT UNDO IT. `said` is null only until somebody
        // has asked, and a listing taken before the ask would otherwise put the control back into
        // the state the reader just left.
        setHeld((h) => (h.said === null ? { ...h, said: r.postponed.includes(slug) } : h)),
      )
      .catch(() => {
        // Not fatal and not silenced elsewhere: the detail read above is what tells a reader the
        // api is unreachable, and failing that read is what draws the error page. A listing that
        // could not be taken leaves the control reading "not set aside", which the first ask
        // corrects, because the server answers with what is on disk rather than what was asked.
      })
  }, [slug])

  // THE CONTROL UNMOUNTS ON SUCCESS, so something has to catch the focus it was holding. Without
  // this a keyboard user is returned to the document body by their own click, which is the one
  // interaction a mouse never notices going wrong.
  useEffect(() => {
    if (again.landed) {
      said.current?.focus()
    }
  }, [again.landed])

  return (
    <Loaded
      what="record"
      subject="This bump"
      failed={failed}
      value={detail}
      header={<PageHeader title="bump" subtitle="—" actions={<Nav current="bumps" />} />}
    >
      {(detail) => {
        const { summary, chain, events, packages, cves, rounds } = detail
        // WHAT THE PAGE BELIEVES ABOUT THIS BUMP BEING HELD: whatever the server last said, whether that
        // came from the listing on load or from an ask. `null` means nobody has answered yet, which is
        // not the same fact as "not held" and is why the record keeps three states rather than two.
        const aside = held.said ?? false
        // A MARKER ONLY MATTERS TO A BUMP RUN.SH WILL STILL LOOK AT. It tests a repository for a
        // settlement first and skips it before it ever consults the postponed directory, so offering
        // this on a settled verdict would offer a write that changes nothing. A bump that IS held keeps
        // the control whatever its verdict, or there would be no way back from it.
        const holdable = aside || summary.verdict === 'bumping' || summary.verdict === 'queued'
        const shown = only === undefined ? events : events.filter((e) => e.agent === only)
        const at = (t: Tab, agent?: string) =>
          href(
            `/bump/?slug=${encodeURIComponent(slug)}&tab=${t}` +
              (agent === undefined ? '' : `&agent=${encodeURIComponent(agent)}`),
          )

        return (
          <>
            <PageHeader
              title={summary.repo}
              subtitle={
                <>
                  JDK {summary.from} → {summary.to} · {summary.sha.slice(0, 12)} ·{' '}
                  <VerdictPill verdict={summary.verdict} />
                  {/* A REASON IS A SENTENCE, and a sentence does not belong in a right-aligned row of
                      corner controls, where it either truncates to nothing or deforms the header. It
                      also must not be carried by colour alone, so the red is on one bolded word and
                      the rest is prose. */}
                  {again.refused === '' ? null : (
                    <div role="alert" style={HEADER_NOTE}>
                      <b style={{ color: 'var(--danger)' }}>Not queued.</b> {again.refused}
                    </div>
                  )}
                  {hold.refused === '' ? null : (
                    <div role="alert" style={HEADER_NOTE}>
                      <b style={{ color: 'var(--danger)' }}>Unchanged.</b> {hold.refused}
                    </div>
                  )}
                  {/* THE STATE, IN PROSE, ONLY WHERE IT IS THE ANSWER TO A QUESTION. A held bump looks
                      otherwise exactly like one waiting for a lane, and the difference between the two is
                      hours. The component owns the wording, so the tooltip and the note cannot disagree
                      about what postponement means. */}
                  {aside ? <SetAsideNote why={held.why} /> : null}
                </>
              }
              back={{ label: 'bumps', href: href('/') }}
              actions={
                <>
                  {/* A SETTLED VERDICT IS ONLY TRUE OF THE HARNESS THAT REACHED IT. The floors, the
                      prompts and the tools all moved today, so a verdict from this morning was decided
                      by an agent that no longer exists.

                      Three renderings, not two. The word comes back at the one moment it says something
                      the pill does not yet: that this click landed. It is also what the focus lands on,
                      which is why it is an element and not a pill variant.

                      WITHHELD ONLY WHILE A LANE HOLDS IT. A queued bump keeps its button: it can sit a
                      long time, because the drainer waits for a free lane and a lane runs for hours, and
                      a reader with no way to ask has no way to tell waiting from lost. Two repositories
                      sat requeued and unclaimed for ninety minutes with nothing on the page to say so.
                      Asking again is answered rather than duplicated: the server sees the row already
                      pending and says so instead of adding a second. */}
                  {again.landed ? (
                    <span ref={said} tabIndex={-1} role="status" style={SAID}>
                      queued
                    </span>
                  ) : summary.verdict === 'bumping' ? null : (
                    <button
                      type="button"
                      aria-label={
                        again.refused === ''
                          ? 'Run this bump again'
                          : 'Run this bump again. The last ask was refused'
                      }
                      title="Run this bump again"
                      aria-busy={again.busy || undefined}
                      // aria-disabled rather than the real attribute, which would drop the control out
                      // of the tab order mid-action and strand a keyboard user on the body.
                      aria-disabled={again.busy || undefined}
                      onClick={() => {
                        if (!again.busy) {
                          again.ask()
                        }
                      }}
                      style={{
                        ...CORNER_BUTTON,
                        ...(again.refused === '' ? null : CORNER_REFUSED),
                        ...(again.busy ? CORNER_BUSY : null),
                      }}
                    >
                      {/* DRAWN, NOT TYPED. Every unicode repeat mark is at the mercy of whichever face
                          the reader's machine falls back to, and U+21BB lands hairline beside the dense
                          U+2699 gear it has to sit next to; a path takes currentColor for both themes
                          and a stroke width that can be matched to the gear by eye.

                          r=5 about (8,8). large-arc=1 with sweep=1 picks the 300° clockwise sweep, so
                          the tangent at the terminus is exactly (1,0) and the head is axis-aligned; a
                          3.2 base against a 1.5 stroke still reads as an arrow at 16px, where a
                          narrower one becomes a blob. */}
                      <svg
                        viewBox="0 0 16 16"
                        aria-hidden="true"
                        focusable="false"
                        style={{ ...TURN, transform: `rotate(${again.asks * 360}deg)` }}
                      >
                        <path
                          d="M12.33 5.5A5 5 0 1 1 8 3"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="1.7"
                          strokeLinecap="round"
                        />
                        <path d="M11.2 3L8 1.4L8 4.6Z" fill="currentColor" />
                      </svg>
                    </button>
                  )}
                  {/* ITS CONVERSE, BESIDE IT. One asks for the work sooner, the other gives its lane to
                      work that can progress, and a reader meeting them together should be able to tell
                      which is which without pressing either: same box, same measured mark size, same
                      refusal shape, opposite drawing. */}
                  {holdable ? (
                    <SetAsideButton
                      setAside={aside}
                      busy={hold.busy}
                      refused={hold.refused !== ''}
                      onAsk={() => hold.ask(!aside)}
                    />
                  ) : null}
                  <Nav current="bumps" />
                </>
              }
            />

            <div style={{ padding: '14px 24px 0' }}>
              <ChainStrip
                stages={chain}
                {...(only === undefined ? {} : { only })}
                hrefFor={(agent) => at('record', agent)}
                allHref={at('record')}
              />
            </div>

            <div style={{ padding: '12px 24px 0' }}>
              <TabRow
                label="This bump"
                tabs={[
                  { label: 'summary', href: at('summary'), current: tab === 'summary' },
                  { label: 'the record', href: at('record'), current: tab === 'record' },
                ]}
              />
            </div>

            {tab === 'summary' ? (
              <>
                {summary.because == null ? null : (
                  <Section title="what it settled as">
                    <Card>
                      <div
                        style={{
                          fontSize: '12.5px',
                          color: 'var(--text-secondary)',
                          whiteSpace: 'pre-wrap',
                        }}
                      >
                        {summary.because}
                      </div>
                    </Card>
                  </Section>
                )}

                {/* Nothing to show only when there was nothing before AND nothing measured after.
                    `after` is null on every bump that did not reach a green gate, and null is not
                    zero: a project with 337 findings and no second scan still has 337. */}
                {cves.before === 0 && (cves.after ?? 0) === 0 ? null : (
                  <Section title="vulnerabilities">
                    <SecurityDelta
                      before={cves.before}
                      after={cves.after}
                      distinctBefore={cves.distinctBefore}
                      distinctAfter={cves.distinctAfter}
                    />
                  </Section>
                )}

                {/* ONLY WHERE THERE IS A HISTORY TO SHOW, which is roughly one bump in eight.
                    A lane has a wall-clock budget and most bumps finish inside their first one, so
                    a section that appeared on every record would say nothing on nearly all of
                    them. Where it does appear it answers the question the round number on the row
                    above cannot: whether those rounds continued each other or started over. */}
                {rounds.length === 0 ? null : (
                  <Section title="rounds">
                    <RoundHistory rounds={rounds} />
                  </Section>
                )}

                <Section title="dependencies" gutter="heading" id="dependencies">
                  <PackageTable packages={packages} />
                </Section>
              </>
            ) : (
              <Section
                title={
                  only === undefined
                    ? `the record · ${events.length.toLocaleString()} event(s)`
                    : `the record · what ${only} did · ${shown.length.toLocaleString()} of ${events.length.toLocaleString()}`
                }
              >
                {only === undefined ? null : (
                  <p style={{ margin: '0 0 10px', fontSize: '12px' }}>
                    <a href={at('record')} style={{ color: 'var(--accent-primary)' }}>
                      show every agent
                    </a>
                  </p>
                )}
                <EventFeed events={shown} />
              </Section>
            )}
          </>
        )
      }}
    </Loaded>
  )
}

/** What the rerun endpoint answers: whether it queued the bump, and why not when it did not. */
type Requeue = { queued: boolean; why?: string }

/**
 * WHAT THE SERVER LAST SAID ABOUT THIS BUMP BEING HELD, which is the page's own fact rather than
 * the ask machine's: it arrives from the postponed listing on load as well as from an ask.
 *
 * `said` IS THE SERVER'S ANSWER, NOT THE READER'S REQUEST, and `null` until there has been one. It
 * is a tri-state on purpose: a page that stored `false` before anyone asked could not tell a bump
 * nobody has touched from one an ask just released.
 *
 * `why` is the reason recorded on the marker, which is mostly the supervisor's words rather than
 * this page's, and is a different thing from a refusal.
 */
type Held = { said: boolean | null; why: string }

/**
 * THE POSTPONE ENDPOINT, WRITTEN DOWN IN ONE PLACE.
 *
 * ONE ROUTE, BOTH DIRECTIONS, BY CHOICE RATHER THAN BY CONSTRAINT. The server offers a pair,
 * /api/postpone and /api/resume, and either of them also takes an explicit `state`. This page uses
 * the one route and names the state, because what makes the direction safe is naming it rather
 * than which path carries it, and a caller with one url has one place to be wrong about.
 *
 * `state` IS NAMED RATHER THAN LEFT TO FLIP. The endpoint will flip whatever it finds if the
 * parameter is left off, but a flip acts on what the page believed when it rendered, and two
 * readers on the same bump would then undo each other. Naming the state wanted makes a second
 * click a no-op instead of a reversal.
 *
 * `postponed` in the reply is READ BACK OFF DISK rather than echoed from the request, so it is the
 * state the launcher will actually see. `error` is the refusal; `why` is the reason recorded on the
 * marker, which is a different thing and mostly the supervisor's words rather than this page's.
 */
type Postponement = {
  postponed: boolean
  was: boolean
  changed: boolean
  /** The reason on the marker, empty when there is none to read. Not a refusal. */
  why: string
  /** The file the server wrote, which is the one run.sh tests for. */
  marker?: string
  /** Present only when the write was refused. */
  error?: string
}

/**
 * EVERY BUMP CURRENTLY SET ASIDE, in one listing, because a toggle has to render in the state it is
 * already in and there is no per-bump field carrying it. The markers are one flat directory, so the
 * whole answer is a directory listing and the page asks for it once.
 *
 * The names in it are already flattened by the server, with the same rule that names a bump's
 * results directory, which is the slug this page is holding. So membership is a plain comparison
 * and the flattening is not written out here a second time. A marker under a name nothing tests for
 * is the bug this codebase keeps finding, and it is found by writing the rule once.
 */
type Postponements = { dir: string; postponed: string[] }

/**
 * The rerun mark's own sizing, which is CORNER_MARK plus the one thing only it does.
 *
 * ONE TURN PER ASK, FROM A COUNTER THAT ONLY GOES UP, so it never rewinds when an ask is refused.
 * A transition rather than a keyframe: tokens.css is the portal's file, taken verbatim and read by
 * two tools, and its reduced-motion block already clamps transitions for nothing.
 */
const TURN = {
  ...CORNER_MARK,
  transition: 'transform 600ms cubic-bezier(.4,0,.2,1)',
} as const

const SAID = { fontSize: '12px', color: 'var(--text-tertiary)', padding: '0.2rem 0.35rem' } as const

export default function Page() {
  return (
    <Suspense fallback={<EmptyNote>Reading the record…</EmptyNote>}>
      <BumpPage />
    </Suspense>
  )
}
