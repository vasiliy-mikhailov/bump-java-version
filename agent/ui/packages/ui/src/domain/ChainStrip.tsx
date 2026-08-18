import type { ChainStage, ChainStep } from '@bjv/types'
import type { Style } from '../primitives/style'

export type ChainStripProps = {
  stages: ChainStage[]
  /** The agent whose events are currently filtered in, if any. */
  only?: string
  /** Builds the link that filters to one agent. Absent renders the strip read-only. */
  hrefFor?: (agent: string) => string
  /** The link back to everything. */
  allHref?: string
}

/**
 * THE CHAIN, DRAWN AS THE TRIPLETS IT IS.
 *
 * This was a strip of PAIRS, and the pairing was the lie. Every stage showed a producer and a
 * critic, so a reader could not tell which of the two decided the approach and which judged it —
 * and the chain had four words for the same job: `surveyor`, `bumper`, `troubleshooter`, `-critic`.
 * Plan, do, verify is the vocabulary now, and the strip says it.
 *
 * IT IS DRAWN WHOLE, including stages the bump never reached. A strip that lists only who has spoken
 * shows a chain of fourteen as a chain of two and hides where the run actually stopped. A dim stage
 * is information: it answers "how far did this get", which is the first question anyone asks.
 *
 * The arrow before a verifier loops when the doer ran more than once, because that is precisely the
 * verifier having sent the work back — the one event on the page that says the loop did its job.
 */
export function ChainStrip({ stages, only, hrefFor, allHref }: ChainStripProps) {
  const top = stages.filter((s) => s.within === '')
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px', alignItems: 'flex-start' }}>
      {allHref === undefined ? null : (
        <a
          href={allHref}
          style={{
            ...pillish,
            background: only === undefined ? 'var(--accent-primary)' : 'var(--bg-subtle)',
            color: only === undefined ? 'var(--accent-on-action)' : 'var(--text-secondary)',
            border: '1px solid var(--border-soft)',
          }}
        >
          everything
        </a>
      )}
      {top.map((stage) => (
        <Stage
          key={stage.title}
          stage={stage}
          nested={stages.filter((s) => s.within === stage.title)}
          only={only}
          hrefFor={hrefFor}
        />
      ))}
    </div>
  )
}

const pillish: Style = {
  display: 'inline-block',
  padding: '5px 11px',
  borderRadius: '6px',
  fontSize: '12px',
  textDecoration: 'none',
  whiteSpace: 'nowrap',
}

function Stage({
  stage,
  nested,
  only,
  hrefFor,
}: {
  stage: ChainStage
  nested: ChainStage[]
  // NOT optional, deliberately: these are always passed, sometimes as undefined. Under
  // exactOptionalPropertyTypes those are different types, and conflating them is what the flag is
  // for. The public props on ChainStrip stay optional because a caller may genuinely omit them.
  only: string | undefined
  hrefFor: ((agent: string) => string) | undefined
}) {
  const reached = stage.steps.some((s) => s.spoke > 0)
  const box: Style = {
    display: 'flex',
    flexDirection: 'column',
    gap: '6px',
    padding: '9px 11px',
    borderRadius: '8px',
    border: '1px solid var(--border-soft)',
    background: 'var(--bg-card)',
    opacity: reached ? 1 : 0.45,
  }
  return (
    <div style={box}>
      <span
        style={{
          fontSize: '10px',
          textTransform: 'uppercase',
          letterSpacing: '0.06em',
          color: 'var(--text-tertiary)',
        }}
      >
        {stage.title}
      </span>
      <div style={{ display: 'flex', alignItems: 'center', gap: '5px', flexWrap: 'wrap' }}>
        {stage.steps.map((step, i) => (
          <span key={step.name} style={{ display: 'contents' }}>
            {i > 0 ? <Arrow looped={(stage.steps[i - 1]?.spoke ?? 0) > 1} /> : null}
            <StepPill step={step} only={only} hrefFor={hrefFor} />
          </span>
        ))}
      </div>
      {nested.length === 0 ? null : (
        <div
          style={{
            // ACROSS, NOT DOWN. A stage that runs three stages inside it was three times the
            // height of every other box, and in a wrapping row one tall item sets the height of
            // the whole line: the strip grew a hole beside it the size of two stages. Sideways,
            // the widest box is wide rather than tall and the chain reads as the sequence it is.
            display: 'flex',
            flexDirection: 'row',
            flexWrap: 'wrap',
            alignItems: 'flex-start',
            gap: '6px',
            marginTop: '2px',
            paddingLeft: '10px',
            borderLeft: '2px solid var(--border-soft)',
          }}
        >
          {nested.map((n) => (
            <Stage key={n.title} stage={n} nested={[]} only={only} hrefFor={hrefFor} />
          ))}
        </div>
      )}
    </div>
  )
}

/** The arrow, and the loop it becomes when a doer ran more than once. */
function Arrow({ looped }: { looped: boolean }) {
  return (
    <span
      aria-hidden="true"
      title={looped ? 'the verifier sent this back' : undefined}
      style={{ color: looped ? 'var(--verdict-again)' : 'var(--text-tertiary)', fontSize: '12px' }}
    >
      {looped ? '↺' : '→'}
    </span>
  )
}

/**
 * One step. Its ROLE decides the colour and its KIND decides the border, because those answer two
 * different questions: what does this do, and can it change the workspace.
 */
function StepPill({
  step,
  only,
  hrefFor,
}: {
  step: ChainStep
  only: string | undefined
  hrefFor: ((agent: string) => string) | undefined
}) {
  const tone = step.agent ? `var(--role-${step.role})` : 'var(--step-deterministic)'
  const style: Style = {
    '--step-tone': tone,
    ...pillish,
    fontSize: '11.5px',
    padding: '3px 9px',
    color: step.spoke === 0 ? 'var(--text-tertiary)' : 'var(--step-tone)',
    background:
      step.name === only
        ? 'color-mix(in srgb, var(--step-tone) 22%, transparent)'
        : 'color-mix(in srgb, var(--step-tone) 8%, transparent)',
    border: `1px ${step.agent ? 'solid' : 'dashed'} color-mix(in srgb, var(--step-tone) 34%, transparent)`,
    opacity: step.spoke === 0 ? 0.7 : 1,
  }
  const label = (
    <>
      {shortName(step)}
      {step.spoke > 1 ? (
        <span style={{ opacity: 0.75 }}>
          {' '}
          {step.spoke}
        </span>
      ) : null}
    </>
  )
  // A step that never spoke has nothing to filter to, and a link that filters to nothing is a link
  // that answers a click with an empty page.
  if (hrefFor === undefined || step.spoke === 0 || !step.agent) {
    return (
      <span style={style} title={step.agent ? step.name : 'deterministic'}>
        {label}
      </span>
    )
  }
  return (
    <a href={hrefFor(step.name)} style={style} title={step.name}>
      {label}
    </a>
  )
}

/**
 * A TRIPLET MEMBER SAYS ITS ROLE, NOT ITS STAGE TWICE.
 *
 * The box already carries the stage in small caps, so `survey-planner` inside a box labelled SURVEY
 * spends most of its width repeating the label directly above it. Three stages of that and the
 * strip is a column of prefixes with the distinguishing word squeezed off the end. The sibling
 * writes plan, do, verify, and it reads at a glance because the only thing varying between pills is
 * the only thing that differs.
 *
 * DETERMINISTIC STEPS KEEP THEIR NAMES. `baseline` and `the three passes` are not triplet members
 * and their names are the whole information: shortening them to "do" would say nothing at all. The
 * full agent name stays on the hover title either way, since that is the string a reader would take
 * to the trace.
 */
const SHORT = { planner: 'plan', doer: 'do', verifier: 'verify' } as const

function shortName(step: ChainStep): string {
  return step.agent ? SHORT[step.role] : step.name
}
