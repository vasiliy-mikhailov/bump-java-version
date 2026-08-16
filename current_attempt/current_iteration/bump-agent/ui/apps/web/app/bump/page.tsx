'use client'

import { Suspense, useEffect, useRef, useState } from 'react'
import type { BumpDetail } from '@bjv/types'
import {
  Card,
  CORNER,
  ChainStrip,
  EmptyNote,
  EventFeed,
  PackageTable,
  PageHeader,
  SecurityDelta,
  TabRow,
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
  const [again, setAgain] = useState<Rerun>({ turns: 0, busy: false, queued: false, why: '' })
  const said = useRef<HTMLSpanElement>(null)

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
        setDetail((held) =>
          held === null ? held : { ...held, events: [...held.events, e as TraceEvent] }),
    })
    // Deliberately not depending on `detail`: it changes with every event that arrives, and
    // resubscribing on each one would reopen the stream forever.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slug, detail !== null])

  // THE CONTROL UNMOUNTS ON SUCCESS, so something has to catch the focus it was holding. Without
  // this a keyboard user is returned to the document body by their own click, which is the one
  // interaction a mouse never notices going wrong.
  useEffect(() => {
    if (again.queued) {
      said.current?.focus()
    }
  }, [again.queued])

  /**
   * Ask for this bump again.
   *
   * A REFUSAL IS A 200. The server answers {queued:false,why} when there is nothing settled under
   * the slug, so the catch here is for the network and for the page being served without its api,
   * and the two have to read differently or a wrong slug looks like an outage.
   */
  const askAgain = () => {
    setAgain((a) => ({ turns: a.turns + 1, busy: true, queued: false, why: '' }))
    post<{ queued: boolean; why?: string }>(`/api/rerun?slug=${encodeURIComponent(slug)}`)
      .then((r) => {
        setAgain((a) => ({
          ...a,
          busy: false,
          queued: r.queued,
          why: r.queued ? '' : (r.why ?? 'the server declined without saying why'),
        }))
        // The bump IS queued now, so the pill says so rather than going on showing a verdict that
        // is about to be replaced. Which also takes the control away, and should: a second ask
        // would be a second row in the manifest for a bump already waiting for a lane.
        if (r.queued) {
          setDetail((d) => (d === null ? d : { ...d, summary: { ...d.summary, verdict: 'queued' } }))
        }
      })
      .catch((e: Error) =>
        setAgain((a) => ({ ...a, busy: false, why: `the request itself failed: ${e.message}` })),
      )
  }

  if (failed !== null) {
    return (
      <>
        <PageHeader title="bump" subtitle="—" actions={<Nav current="bumps" />} />
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>This bump could not be read: {failed}</EmptyNote>
        </div>
      </>
    )
  }
  if (detail === null) {
    return (
      <>
        <PageHeader title="bump" subtitle="—" actions={<Nav current="bumps" />} />
        <div style={{ padding: '0 24px' }}>
          <EmptyNote>Reading the record…</EmptyNote>
        </div>
      </>
    )
  }

  const { summary, chain, events, packages, cves } = detail
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
            {again.why === '' ? null : (
              <div role="alert" style={REFUSED_NOTE}>
                <b style={{ color: 'var(--danger)' }}>Not queued.</b> {again.why}
              </div>
            )}
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
            {again.queued ? (
              <span ref={said} tabIndex={-1} role="status" style={SAID}>
                queued
              </span>
            ) : summary.verdict === 'bumping' ? null : (
              <button
                type="button"
                aria-label={
                  again.why === ''
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
                    askAgain()
                  }
                }}
                style={{
                  ...CORNER_BUTTON,
                  ...(again.why === '' ? null : REFUSED),
                  ...(again.busy ? BUSY : null),
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
                  style={{ ...TURN, transform: `rotate(${again.turns * 360}deg)` }}
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

          <section id="dependencies" style={{ margin: '0 0 22px', scrollMarginTop: '12px' }}>
            <h2 style={LABEL}>dependencies</h2>
            <PackageTable packages={packages} />
          </section>
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
}

/** What asking for a rerun has done so far. One record because the four move together. */
type Rerun = { turns: number; busy: boolean; queued: boolean; why: string }

/**
 * THE BUTTON TWIN OF THE CORNER GEAR.
 *
 * It spreads CORNER rather than restating its numbers, so the two corner controls share one box by
 * construction and cannot drift apart the next time either is adjusted. A button does not inherit
 * fontFamily, and the `font` shorthand would take CORNER's fontSize down with it, so the family is
 * named on a line of its own.
 *
 * The border is reserved transparent and paid for out of the padding: a refusal can turn it red
 * without moving the gear beside it by a pixel. No hover, because the gear has none and matching
 * that corner is the whole argument for this shape.
 */
const CORNER_BUTTON = {
  ...CORNER,
  display: 'inline-flex',
  alignItems: 'center',
  appearance: 'none',
  background: 'none',
  border: '1px solid transparent',
  padding: '0 calc(0.35rem - 1px)',
  margin: 0,
  fontFamily: 'inherit',
  cursor: 'pointer',
  transition: 'color 120ms ease, border-color 120ms ease',
} as const

/**
 * 1.25em, MEASURED OFF THE RENDERED PAGE RATHER THAN REASONED ABOUT.
 *
 * The gear beside it is emoji-presented: U+2699 with no variation selector falls through to the
 * colour emoji face, which is why it is blue in a monochrome header, and an emoji glyph overshoots
 * its em. Measured on the real page at 1.25rem, the gear's ink is 22.2px square while a 1em drawing
 * came out at 12. Guessing from a text face said the opposite, which is why this number is taken
 * from a screenshot of the thing itself.
 *
 * 1.25em puts the arrow at about 85 per cent of the gear's diameter, where a stroked mark reads as
 * the same weight as a filled one rather than as a larger, thinner ring. If the gear ever loses its
 * emoji presentation this drops back to roughly 0.8em, so re-measure rather than trusting the
 * number. Vertical padding is nil because the drawing is already taller than the glyph's line box.
 *
 * ONE TURN PER ASK, FROM A COUNTER THAT ONLY GOES UP, so it never rewinds when an ask is refused.
 * A transition rather than a keyframe: tokens.css is the portal's file, taken verbatim and read by
 * two tools, and its reduced-motion block already clamps transitions for nothing.
 */
const TURN = {
  width: '1.25em',
  height: '1.25em',
  display: 'block',
  transition: 'transform 600ms cubic-bezier(.4,0,.2,1)',
} as const

const BUSY = { opacity: 0.55, cursor: 'progress' } as const
const REFUSED = { color: 'var(--danger)', borderColor: 'var(--danger)' } as const
const SAID = { fontSize: '12px', color: 'var(--text-tertiary)', padding: '0.2rem 0.35rem' } as const
const REFUSED_NOTE = { marginTop: '4px', maxWidth: '60ch', color: 'var(--text-secondary)' } as const

const LABEL = {
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  margin: '18px 24px 10px',
} as const

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section style={{ margin: '0 0 22px', padding: '0 24px' }}>
      <h2 style={{ ...LABEL, margin: '18px 0 10px' }}>{title}</h2>
      {children}
    </section>
  )
}

export default function Page() {
  return (
    <Suspense fallback={<EmptyNote>Reading the record…</EmptyNote>}>
      <BumpPage />
    </Suspense>
  )
}
