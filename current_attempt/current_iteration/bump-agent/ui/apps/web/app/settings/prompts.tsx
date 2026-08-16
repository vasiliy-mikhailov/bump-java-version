'use client'

import { useEffect, useState } from 'react'
import type { AgentPrompt } from '@bjv/types'
import { CARDS, EmptyNote, PromptCard, TabRow } from '@bjv/ui'
import { href, read } from '@/lib/api'

const HOPS = ['8-11', '11-17', '17-21', '21-25'] as const

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

  useEffect(() => {
    const wanted = new URLSearchParams(window.location.search).get('hop') ?? '17-21'
    const valid = (HOPS as readonly string[]).includes(wanted) ? wanted : '17-21'
    setHop(valid)
    read<AgentPrompt[]>(`/api/settings?hop=${valid}`)
      .then(setPrompts)
      .catch((e: Error) => setFailed(e.message))
  }, [])

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
  const stages: { title: string; within: string; loop: string; agents: AgentPrompt[] }[] = []
  for (const p of prompts ?? []) {
    const last = stages[stages.length - 1]
    if (last !== undefined && last.title === p.stage) {
      last.agents.push(p)
    } else {
      stages.push({ title: p.stage, within: p.within, loop: p.loop, agents: [p] })
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
  }

  const blocks: Block[] = []
  for (let i = 0; i < stages.length; i += 1) {
    const stage = stages[i]
    if (stage === undefined || stage.within !== '') continue

    const children: typeof stages = []
    for (let j = i + 1; j < stages.length; j += 1) {
      const child = stages[j]
      if (child === undefined || child.within !== stage.title) break
      children.push(child)
    }

    if (children.length === 0) {
      blocks.push({ key: stage.title, title: stage.title, depth: 0, agents: stage.agents, opens: '' })
      continue
    }

    // Open with everything that is not the verdict on the block: in practice the planner.
    const closers = stage.agents.filter((a) => a.role === 'verifier')
    const openers = stage.agents.filter((a) => a.role !== 'verifier')
    blocks.push({
      key: stage.title + ':open',
      title: stage.title,
      depth: 0,
      agents: openers,
      opens: stage.loop,
    })
    for (const child of children) {
      blocks.push({ key: child.title, title: child.title, depth: 1, agents: child.agents, opens: '' })
    }
    blocks.push({
      key: stage.title + ':close',
      title: stage.title,
      depth: 0,
      agents: closers,
      opens: '',
    })
  }

  return (
    <>
      <TabRow
        label="Hop"
        tabs={HOPS.map((h) => ({
          label: h.replace('-', ' → '),
          href: href(`/settings/?a=prompts&hop=${h}`),
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
        {failed !== null ? (
          <EmptyNote>The prompts could not be read: {failed}</EmptyNote>
        ) : prompts === null ? (
          <EmptyNote>Reading the prompts…</EmptyNote>
        ) : (
          blocks.map((block) => (
            <section
              key={block.key}
              style={{
                margin: '0 0 22px',
                // INDENTED WITH A RULE DOWN THE LEFT, because a block that is only shifted right
                // has no visible end, and the end is half of what a block tells a reader.
                marginLeft: block.depth === 0 ? 0 : '22px',
                paddingLeft: block.depth === 0 ? 0 : '16px',
                borderLeft: block.depth === 0 ? undefined : '2px solid var(--border-strong)',
              }}
            >
              <h2
                style={{
                  fontSize: '11px',
                  textTransform: 'uppercase',
                  letterSpacing: '.06em',
                  color: 'var(--text-tertiary)',
                  fontWeight: 500,
                  margin: '0 0 10px',
                }}
              >
                {block.title}
                {block.opens === '' ? null : (
                  <span style={{ color: 'var(--accent-primary)', marginLeft: '8px' }}>
                    {'\u21bb '}
                    {block.opens}
                    {' \u2014 everything below runs inside this'}
                  </span>
                )}
              </h2>
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
            </section>
          ))
        )}
      </div>
    </>
  )
}
