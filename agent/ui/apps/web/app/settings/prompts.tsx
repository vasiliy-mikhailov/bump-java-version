'use client'

import { useEffect, useState } from 'react'
import type { AgentPrompt } from '@bjv/types'
import { CARDS, HEADING, Loaded, PromptCard, TabRow, lanesOf } from '@bjv/ui'
import { href, read } from '@/lib/api'
import { BomFile, type Bom } from './bom'

const HOPS = ['8-11', '11-17', '17-21', '21-25'] as const

/**
 * THE HEAD OF A LANE, WHICH IS THE THING THAT MAKES IT A LANE.
 *
 * Three platforms in three columns is only a comparison if a reader can tell which column is
 * which without reading a suffix on every card, and it is only true if the columns are columns.
 * They were neither: the cards sat in an auto-fit grid whose column count came from the viewport,
 * so the alignment held at one width and quietly failed at another.
 */
const LANE_HEAD = {
  position: 'sticky' as const,
  top: 0,
  padding: '0 2px 6px',
  fontSize: '11px',
  textTransform: 'uppercase' as const,
  letterSpacing: '.08em',
  fontWeight: 700,
  color: 'var(--text-primary)',
  borderBottom: '2px solid var(--accent-primary)',
  marginBottom: '4px',
}

/**
 * A PLATFORM WITH NO AGENT FOR THIS ROLE, said out loud.
 *
 * Every agent has all three today, so this should never render. It exists because the alternative
 * to drawing a hole is closing it, and closing it moves every card after it one lane left under a
 * heading that now lies about it.
 */
const LANE_GAP = {
  border: '1px dashed var(--border-soft)',
  borderRadius: '8px',
  padding: '12px 14px',
  fontSize: '12px',
  color: 'var(--text-tertiary)',
}

/**
 * EVERY PROMPT, FOR ONE HOP, IN THE ORDER THE CHAIN REACHES THEM.
 *
 * The same agent is a different agent on a different hop: an 8-to-11 pin planner is not shown the
 * Kotlin rule for JDK 25, because a rule that cannot fire is not merely wasted context, it is an
 * invitation to apply it anyway. So the hop is the tab, and what is listed under it is what that
 * hop's agents will actually be handed.
 *
 * THE HOP LIVES IN THE URL. It was in component state as well as in a tab row, which drew the four
 * hops twice — once as tabs and once as pills — and meant a reader could not send anybody a link to
 * the prompts they were looking at. One selector, and it is the address bar.
 */
export function PromptsSection({ onCount }: { onCount: (n: number, stages: number) => void }) {
  const [hop, setHop] = useState('17-21')
  const [prompts, setPrompts] = useState<AgentPrompt[] | null>(null)
  const [failed, setFailed] = useState<string | null>(null)
  // THE LISTS THE PIN PHASES WORK TO, on this page rather than a tab away. A reader looking at
  // before-pins had to remember which half it read, go elsewhere and come back; the stage names
  // the list, so the list belongs under the stage.
  const [bom, setBom] = useState<Bom | null>(null)
  const [typed, setTyped] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [said, setSaid] = useState<Record<string, string>>({})

  useEffect(() => {
    const wanted = new URLSearchParams(window.location.search).get('hop') ?? '17-21'
    const valid = (HOPS as readonly string[]).includes(wanted) ? wanted : '17-21'
    setHop(valid)
    read<AgentPrompt[]>(`/api/settings?hop=${valid}`)
      .then(setPrompts)
      .catch((e: Error) => setFailed(e.message))
    read<Bom>(`/api/settings/bom?hop=${valid}`)
      .then(setBom)
      .catch(() => undefined)
  }, [])

  const saveBom = (part: string, text: string) => {
    setBusy(part)
    setSaid((s) => ({ ...s, [part]: '' }))
    fetch(href(`/api/settings/bom?hop=${hop}&part=${part}`), { method: 'POST', body: text })
      .then((r) => r.json())
      .then((r: { saved: boolean; why?: string; rows?: number; edited?: boolean }) => {
        setBusy(null)
        if (!r.saved) {
          // REFUSED HERE RATHER THAN AT THE NEXT BUMP. A row that cannot be read throws at load,
          // and load happens inside a lane, where nobody who typed it would ever see the message.
          setSaid((s) => ({ ...s, [part]: `not saved: ${r.why ?? 'unknown'}` }))
          return
        }
        setSaid((s) => ({
          ...s,
          [part]: r.edited
            ? `saved, ${r.rows} row(s); this replaces the built-in`
            : `reverted, ${r.rows} row(s) from the code`,
        }))
        setTyped((s) => {
          const next = { ...s }
          delete next[part]
          return next
        })
        read<Bom>(`/api/settings/bom?hop=${hop}`).then(setBom).catch(() => undefined)
        // The pin count in the heading is served with the prompts, so it has to be re-read or the
        // number above the box disagrees with the rows inside it.
        read<AgentPrompt[]>(`/api/settings?hop=${hop}`).then(setPrompts).catch(() => undefined)
      })
      .catch((e: Error) => {
        setBusy(null)
        setSaid((s) => ({ ...s, [part]: `not saved: ${e.message}` }))
      })
  }

  // REFETCH AFTER A WRITE rather than patching the row in place. The server decides what is in
  // force — it can refuse a save, and an empty one it does — so the page asks rather than assumes.
  const refetch = () =>
    read<AgentPrompt[]>(`/api/settings?hop=${hop}`)
      .then(setPrompts)
      .catch((e: Error) => setFailed(e.message))

  const write = (name: string, body: Record<string, unknown>) =>
    read<unknown>('/api/settings/prompt', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ hop, name, ...body }),
    }).then(refetch)

  useEffect(() => {
    if (prompts !== null) {
      onCount(prompts.length, prompts.filter((p) => p.edited).length)
    }
  }, [prompts, onCount])

  // In the order the chain reaches them, which is the order the server sends them: grouping must
  // not reorder, or the page stops being a picture of the run.
  const stages: {
    title: string
    within: string
    loop: string
    repeats: string
    reads: string
    pins: number
    agents: AgentPrompt[]
  }[] = []
  for (const p of prompts ?? []) {
    const last = stages[stages.length - 1]
    if (last !== undefined && last.title === p.stage) {
      last.agents.push(p)
    } else {
      stages.push({
        title: p.stage,
        within: p.within,
        loop: p.loop,
        repeats: p.repeats,
        reads: p.reads,
        pins: p.pins,
        agents: [p],
      })
    }
  }

  /**
   * THE CHAIN IS A PROGRAM AND IT HAS BLOCKS, so it is drawn as one.
   *
   * modules and troubleshoot each run a sub-chain between their planner and their verifier. Drawn
   * flat, that reads as fourteen stages in a row and hides the two facts that matter most about the
   * shape: the module passes run once per module, and the troubleshoot steps run until the campaign
   * ends or its critic stops it. A reader who does not know that reads a loop body as a straight
   * line and cannot work out why one agent ran eleven times.
   *
   * SO THE ORDER SHOWN IS THE RUN'S, NOT THE DECLARATION'S. Chain lists a stage's own steps
   * together and its children after, because that is how a stage is written; at run time the
   * planner goes first, the body runs between, and the verifier judges what came out of it. A page
   * showing modules-verifier above the passes it is verifying would be a picture of the file rather
   * than of the run, and this page exists to be the second.
   */
  type Block = {
    key: string
    title: string
    depth: number
    agents: AgentPrompt[]
    /** The deterministic step this block opens with, when its doing is a sub-chain. */
    opens: string
    /** How often it runs, empty when once and unconditionally. */
    repeats: string
    /** Which bill of materials it works from, empty when none. */
    reads: string
    /** How many rows that list has for this hop. */
    pins: number
  }

  // ANY DEPTH, NOT ONE LEVEL. The first version collected children only where within matched the
  // stage directly above, so the moment a stage nested two deep -- module-repair-step inside
  // module-repair inside modules -- the collector stopped at it and silently dropped everything
  // after, after-pins included. A page missing a stage is the failure this whole area is about.
  const byParent = new Map<string, typeof stages>()
  for (const s of stages) {
    const kids = byParent.get(s.within) ?? []
    kids.push(s)
    byParent.set(s.within, kids)
  }

  const blocks: Block[] = []
  const emit = (stage: (typeof stages)[number], depth: number) => {
    const children = byParent.get(stage.title) ?? []
    if (children.length === 0) {
      blocks.push({
        key: stage.title,
        title: stage.title,
        depth,
        agents: stage.agents,
        opens: '',
        repeats: stage.repeats,
        reads: stage.reads,
        pins: stage.pins,
      })
      return
    }
    // A block opens with everything that is not the verdict on it, in practice the planner, and
    // closes with the verifier, so the body sits between the two the way it runs.
    blocks.push({
      key: stage.title + ':open',
      title: stage.title,
      depth,
      agents: stage.agents.filter((a) => a.role !== 'verifier'),
      opens: stage.loop,
      repeats: stage.repeats,
      reads: stage.reads,
      pins: stage.pins,
    })
    for (const child of children) {
      emit(child, depth + 1)
    }
    const closers = stage.agents.filter((a) => a.role === 'verifier')
    if (closers.length > 0) {
      blocks.push({
        key: stage.title + ':close',
        title: stage.title,
        depth,
        agents: closers,
        opens: '',
        repeats: '',
        reads: '',
        pins: 0,
      })
    }
  }
  for (const top of byParent.get('') ?? []) {
    emit(top, 0)
  }

  return (
    <>
      <TabRow
        label="Hop"
        tabs={HOPS.map((h) => ({
          label: h.replace('-', ' → '),
          href: href(`/settings/?a=shape&hop=${h}`),
          current: h === hop,
        }))}
      />

      <p
        style={{
          margin: '0 0 18px',
          fontSize: '13px',
          lineHeight: 1.6,
          color: 'var(--text-primary)',
          maxWidth: '78ch',
        }}
      >
        An edit here replaces the built-in entirely &mdash; there is no merge, because a prompt half
        from the code and half from a box is a prompt nobody can read in one place. It takes effect
        on the next bump a lane starts, not on the ones already running, and reverting deletes the
        edit rather than restoring anything: the built-in was never gone. Edits are per hop, since
        the same agent is a different agent on a different hop.
      </p>

      <div>
        <Loaded what="prompts" failed={failed} value={prompts}>
          {() =>
            blocks.map((block) => (
              <section
                key={block.key}
                style={{
                  margin: '0 0 22px',
                  // INDENTED WITH A RULE DOWN THE LEFT, because a block that is only shifted right
                  // has no visible end, and the end is half of what a block tells a reader.
                  // PROPORTIONAL TO DEPTH, not a boolean. It was `depth === 0 ? 0 : 22px`, which
                  // drew a stage nested two deep at the same indent as its own parent, so
                  // module-repair-step read as a sibling of module-repair rather than its body.
                  marginLeft: block.depth === 0 ? 0 : '22px',
                  paddingLeft: block.depth === 0 ? 0 : `${block.depth * 16}px`,
                  borderLeft: block.depth === 0 ? undefined : '2px solid var(--border-strong)',
                }}
              >
                {/* The block's own heading, at the site's one heading treatment, with no leading
                    above it: the block is already separated by its own margin and its rule. */}
                <h2 style={{ ...HEADING, margin: '0 0 10px' }}>
                  {block.title}
                  {/* HOW OFTEN, BESIDE WHERE. Nesting says a stage sits inside modules and says
                      nothing about how many times it happens, and a reader will answer the second
                      question from the first: three peers inside a loop read as three per-module
                      passes, when only bump walks the module list. */}
                  {/* WHICH LIST IT WORKS TO, linked. A reader looking at before-pins should not
                      have to know that the phase runs before the JDK moves in order to work out
                      which half of the bill of materials it is acting on, and a reader editing a
                      list should be able to see which agent will act on what they typed. */}
                  {block.reads === '' ? null : (
                    <a
                      href={href('/settings/?a=bom')}
                      style={{ color: 'var(--accent-primary)', marginLeft: '8px' }}
                    >
                      {block.reads === 'hardens' ? 'hardens the result' : 'enables the bump'}
                      {/* COUNTED FROM THE FILE, not written down beside it. A number typed onto a
                          page goes stale the first time somebody edits the list it describes, and
                          this one is editable from the next tab along. */}
                      {block.pins === 0 ? null : `  ${block.pins} pins`}
                      {' \u2192'}
                    </a>
                  )}
                  {block.repeats === '' ? null : (
                    <span style={{ color: 'var(--text-secondary)', marginLeft: '8px' }}>
                      {block.repeats}
                    </span>
                  )}
                  {block.opens === '' ? null : (
                    <span style={{ color: 'var(--accent-primary)', marginLeft: '8px' }}>
                      {'\u21bb '}
                      {block.opens}
                      {' \u2014 everything below runs inside this'}
                    </span>
                  )}
                </h2>
                {/* THE LIST THIS STAGE WORKS TO, between its name and the agents that read it,
                    which is the order a reader meets them in. */}
                {block.reads === '' || bom === null
                  ? null
                  : bom.files
                      .filter((f) => f.part === block.reads)
                      .map((f) => (
                        <BomFile
                          key={f.part}
                          file={f}
                          typed={typed[f.part]}
                          busy={busy === f.part}
                          said={said[f.part]}
                          onType={(text) => setTyped((s) => ({ ...s, [f.part]: text }))}
                          onSave={(text) => saveBom(f.part, text)}
                        />
                      ))}

                {(() => {
                  const { platforms, rows } = lanesOf(block.agents)
                  if (platforms.length === 0) {
                    return (
                      <div style={CARDS}>
                        {block.agents.map((p) => (
                          <PromptCard
                            key={p.name}
                            prompt={p}
                            onSave={(text) => write(p.name, { text })}
                            onRevert={() => write(p.name, { revert: true })}
                          />
                        ))}
                      </div>
                    )
                  }
                  return (
                    // SCROLLS SIDEWAYS RATHER THAN WRAPPING. Wrapping is what broke it: three lanes
                    // that become two are not two lanes, they are the same six cards in an order
                    // nobody can read. A reader on a narrow screen would rather push the third lane
                    // off the edge and drag it back than be shown a comparison that is not one.
                    <div style={{ overflowX: 'auto', paddingBottom: '4px' }}>
                      <div
                        style={{
                          display: 'grid',
                          gridTemplateColumns: `repeat(${platforms.length}, minmax(360px, 1fr))`,
                          gap: '14px',
                          alignItems: 'start',
                        }}
                      >
                        {platforms.map((platform) => (
                          <div key={`lane:${platform}`} style={LANE_HEAD}>
                            {platform}
                          </div>
                        ))}
                        {rows.flatMap((row) =>
                          row.cells.map((cell) => {
                            const p = cell.prompt
                            return p === null ? (
                              <div key={`${row.stem}@${cell.platform}`} style={LANE_GAP}>
                                no {cell.platform} prompt for {row.stem}
                              </div>
                            ) : (
                              <PromptCard
                                key={p.name}
                                prompt={p}
                                onSave={(text) => write(p.name, { text })}
                                onRevert={() => write(p.name, { revert: true })}
                              />
                            )
                          }),
                        )}
                      </div>
                    </div>
                  )
                })()}
              </section>
            ))
          }
        </Loaded>
      </div>
    </>
  )
}
