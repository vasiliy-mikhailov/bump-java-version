import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { NO_REASON, REQUEST_FAILED, useAsk } from './useAsk'

/**
 * THE REFUSAL HANDLING IS WHAT THESE ASSERT, because the refusal handling is what the two controls
 * this hook replaces got differently.
 *
 * Both of them ask a server to do something to a running sweep, both answer a refusal with 200 and
 * a body, and both are one wrong branch away from telling a reader that the thing they asked for
 * happened when it did not. That is not a cosmetic failure: a reader who believes a bump was
 * requeued stops watching it, and a reader who believes a bump was set aside stops wondering why a
 * lane is not moving.
 */

/** A promise this test decides the fate of, so the busy state can be looked at while it is real. */
function deferred<T>() {
  let settle!: (value: T) => void
  let fail!: (why: Error) => void
  const promise = new Promise<T>((resolve, reject) => {
    settle = resolve
    fail = reject
  })
  return { promise, settle, fail }
}

/** The rerun endpoint's shape: a boolean, and a sentence it may or may not send with it. */
type Queued = { queued: boolean; why?: string }

/** The postpone endpoint's shape: the state it read back off disk, and a refusal if there was one. */
type Postponed = { postponed: boolean; error?: string }

const rerun = (promise: Promise<Queued>) =>
  renderHook(() =>
    useAsk<void, Queued>({
      send: () => promise,
      read: (r) => ({ landed: r.queued, why: r.why }),
    }),
  )

describe('asking a server to do something to a running sweep', () => {
  it('is idle before anybody has asked', () => {
    const { result } = rerun(Promise.resolve({ queued: true }))

    expect(result.current.asks).toBe(0)
    expect(result.current.busy).toBe(false)
    expect(result.current.landed).toBe(false)
    expect(result.current.refused).toBe('')
  })

  it('is busy from the click until the answer, and not one render longer', async () => {
    const answer = deferred<Queued>()
    const { result } = rerun(answer.promise)

    act(() => {
      result.current.ask()
    })
    expect(result.current.busy).toBe(true)
    expect(result.current.landed).toBe(false)

    await act(async () => {
      answer.settle({ queued: true })
    })
    expect(result.current.busy).toBe(false)
    expect(result.current.landed).toBe(true)
  })

  it('counts every ask and never counts one back down', async () => {
    // The rerun control turns its arrow once per ask, off this number. A counter that rewound on a
    // refusal would spin the mark backwards, which reads as the ask being undone rather than
    // declined.
    const first = deferred<Queued>()
    const { result, rerender } = renderHook(
      ({ answer }: { answer: Promise<Queued> }) =>
        useAsk<void, Queued>({
          send: () => answer,
          read: (r) => ({ landed: r.queued, why: r.why }),
        }),
      { initialProps: { answer: first.promise } },
    )

    await act(async () => {
      result.current.ask()
      first.settle({ queued: false, why: 'nothing settled under that slug' })
    })
    expect(result.current.asks).toBe(1)

    const second = deferred<Queued>()
    rerender({ answer: second.promise })
    await act(async () => {
      result.current.ask()
      second.settle({ queued: true })
    })
    expect(result.current.asks).toBe(2)
  })

  it('says the server’s own words when it refused with a reason', async () => {
    const answer = deferred<Queued>()
    const { result } = rerun(answer.promise)

    await act(async () => {
      result.current.ask()
      answer.settle({ queued: false, why: 'nothing settled under that slug' })
    })

    expect(result.current.landed).toBe(false)
    expect(result.current.refused).toBe('nothing settled under that slug')
  })

  it('says something rather than nothing when it refused without one', async () => {
    // A control that goes quiet after a click that changed nothing is worse than one that says it
    // does not know why: the reader cannot tell a refusal from a click that missed the button.
    const answer = deferred<Queued>()
    const { result } = rerun(answer.promise)

    await act(async () => {
      result.current.ask()
      answer.settle({ queued: false })
    })

    expect(result.current.refused).toBe(NO_REASON)
  })

  it('says nothing at all about a refusal when the ask landed', async () => {
    const answer = deferred<Queued>()
    const { result } = rerun(answer.promise)

    await act(async () => {
      result.current.ask()
      // A reason beside a landing is the server being chatty, not a refusal, and rendering it would
      // put a red note under a control that did exactly what it was asked.
      answer.settle({ queued: true, why: 'it was already pending, so this is the same row' })
    })

    expect(result.current.refused).toBe('')
  })

  it('tells a refusal apart from a request that never arrived', async () => {
    // A wrong slug and an unreachable api are different problems with different next moves. Both
    // used to read as "it did not work", which sends a reader to look at the wrong one.
    const answer = deferred<Queued>()
    const { result } = rerun(answer.promise)

    await act(async () => {
      result.current.ask()
      answer.fail(new Error('502 Bad Gateway'))
    })

    expect(result.current.refused).toBe(`${REQUEST_FAILED}502 Bad Gateway`)
    expect(result.current.busy).toBe(false)
    expect(result.current.landed).toBe(false)
  })

  it('clears the last refusal the moment a new ask starts', async () => {
    const first = deferred<Queued>()
    const { result, rerender } = renderHook(
      ({ answer }: { answer: Promise<Queued> }) =>
        useAsk<void, Queued>({
          send: () => answer,
          read: (r) => ({ landed: r.queued, why: r.why }),
        }),
      { initialProps: { answer: first.promise } },
    )

    await act(async () => {
      result.current.ask()
      first.settle({ queued: false, why: 'nothing settled under that slug' })
    })
    expect(result.current.refused).not.toBe('')

    const second = deferred<Queued>()
    rerender({ answer: second.promise })
    act(() => {
      result.current.ask()
    })
    // Still wearing the old refusal while a fresh ask is in flight would be the control lying about
    // the present in order to describe the past.
    expect(result.current.refused).toBe('')
    expect(result.current.busy).toBe(true)
  })

  it('stops claiming it landed while the next ask is in flight', async () => {
    const first = deferred<Queued>()
    const { result, rerender } = renderHook(
      ({ answer }: { answer: Promise<Queued> }) =>
        useAsk<void, Queued>({
          send: () => answer,
          read: (r) => ({ landed: r.queued, why: r.why }),
        }),
      { initialProps: { answer: first.promise } },
    )

    await act(async () => {
      result.current.ask()
      first.settle({ queued: true })
    })
    expect(result.current.landed).toBe(true)

    const second = deferred<Queued>()
    rerender({ answer: second.promise })
    act(() => {
      result.current.ask()
    })
    // Done and busy at once is not a state anything can render: one of the two words is wrong.
    expect(result.current.landed).toBe(false)
    expect(result.current.busy).toBe(true)
  })

  it('reads a refusal off the state the server reports rather than off an error field', async () => {
    // This is the set-aside endpoint. It answers with what is on disk NOW rather than an echo of
    // the request, so an ask that did not move it is a refusal whether or not a reason came with
    // it. Believing the absence of an error here would call a silent no-op a success and leave the
    // control showing the state the reader asked for instead of the one the launcher will see.
    const answer = deferred<Postponed>()
    const { result } = renderHook(() =>
      useAsk<boolean, Postponed>({
        send: () => answer.promise,
        read: (r, wanted) => ({ landed: r.postponed === wanted, why: r.error }),
      }),
    )

    await act(async () => {
      result.current.ask(true)
      answer.settle({ postponed: false })
    })

    expect(result.current.landed).toBe(false)
    expect(result.current.refused).toBe(NO_REASON)
  })

  it('asks with what it was given, so one control can toggle both ways', async () => {
    const send = vi.fn(() => Promise.resolve({ postponed: false }))
    const { result } = renderHook(() =>
      useAsk<boolean, Postponed>({
        send,
        read: (r, wanted) => ({ landed: r.postponed === wanted }),
      }),
    )

    await act(async () => {
      result.current.ask(false)
    })

    expect(send).toHaveBeenCalledWith(false)
    expect(result.current.landed).toBe(true)
  })

  it('hands the page every answer, whether it landed or not', async () => {
    // The postpone endpoint carries the state it read back off disk however it decided, so a page
    // that only looked at answers it liked would go on showing a state the server has already
    // contradicted.
    const heard: Postponed[] = []
    const answer = deferred<Postponed>()
    const { result } = renderHook(() =>
      useAsk<boolean, Postponed>({
        send: () => answer.promise,
        read: (r, wanted) => ({ landed: r.postponed === wanted, why: r.error }),
        onAnswer: (r) => heard.push(r),
      }),
    )

    await act(async () => {
      result.current.ask(true)
      answer.settle({ postponed: false, error: 'the sweep is not running' })
    })

    expect(heard).toEqual([{ postponed: false, error: 'the sweep is not running' }])
    expect(result.current.refused).toBe('the sweep is not running')
  })

  it('hands the page nothing when there was no answer to hand it', async () => {
    const heard: Postponed[] = []
    const answer = deferred<Postponed>()
    const { result } = renderHook(() =>
      useAsk<boolean, Postponed>({
        send: () => answer.promise,
        read: (r, wanted) => ({ landed: r.postponed === wanted }),
        onAnswer: (r) => heard.push(r),
      }),
    )

    await act(async () => {
      result.current.ask(true)
      answer.fail(new Error('connection reset'))
    })

    expect(heard).toEqual([])
    expect(result.current.refused).toBe(`${REQUEST_FAILED}connection reset`)
  })
})
