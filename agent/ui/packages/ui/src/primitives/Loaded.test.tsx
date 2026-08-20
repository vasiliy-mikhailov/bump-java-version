import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Loaded } from './Loaded'

/**
 * THE ORDER OF THE TWO WAITS IS THE FEATURE.
 *
 * A read that failed also has no value, so a screen that asks "is it empty" before it asks "did it
 * fail" describes every failure as a wait, and a reader sits watching a page that will never
 * finish. One of the nine copies this replaces had exactly that shape for a while.
 */
describe('a thing being read', () => {
  it('says it is still reading before there is anything to say', () => {
    render(
      <Loaded what="record" failed={null} value={null}>
        {() => <p>never</p>}
      </Loaded>,
    )

    expect(screen.getByText('Reading the record…')).toBeTruthy()
    expect(screen.queryByText('never')).toBeNull()
  })

  it('reports a failure rather than describing it as still reading', () => {
    render(
      <Loaded what="record" failed="/api/bumps answered 502" value={null}>
        {() => <p>never</p>}
      </Loaded>,
    )

    expect(screen.getByText('The record could not be read: /api/bumps answered 502')).toBeTruthy()
  })

  it('reports the failure even when something was read before it', () => {
    // A page that polls has a value AND a failure the moment a later read goes wrong, and the
    // failure is the news.
    render(
      <Loaded what="record" failed="/api/bumps answered 502" value={['a row']}>
        {(rows) => <p>{rows.length} row(s)</p>}
      </Loaded>,
    )

    expect(screen.getByText('The record could not be read: /api/bumps answered 502')).toBeTruthy()
    expect(screen.queryByText('1 row(s)')).toBeNull()
  })

  it('names the same thing in both sentences', () => {
    // "Reading the queue…" followed by "The subject could not be read" describes two things, and
    // leaves the reader to work out that they are one.
    const { rerender } = render(
      <Loaded what="queue" failed={null} value={null}>
        {() => null}
      </Loaded>,
    )
    expect(screen.getByText('Reading the queue…')).toBeTruthy()

    rerender(
      <Loaded what="queue" failed="no manifest" value={null}>
        {() => null}
      </Loaded>,
    )
    expect(screen.getByText('The queue could not be read: no manifest')).toBeTruthy()
  })

  it('lets a page name the failure after what the reader came for', () => {
    // The bump page waits on a record and fails on a bump, because a reader who followed a link to
    // a repository is not looking for a file.
    render(
      <Loaded what="record" subject="This bump" failed="no such slug" value={null}>
        {() => null}
      </Loaded>,
    )

    expect(screen.getByText('This bump could not be read: no such slug')).toBeTruthy()
  })

  it('keeps the page header up while there is nothing under it', () => {
    // A reader who follows a link into a blank document cannot tell a slow read from a broken one,
    // and has nothing to go back with.
    render(
      <Loaded what="record" failed={null} value={null} header={<h1>bumps</h1>}>
        {() => null}
      </Loaded>,
    )

    expect(screen.getByText('bumps')).toBeTruthy()
    expect(screen.getByText('Reading the record…').parentElement?.getAttribute('style')).toContain(
      'padding: 0px 24px',
    )
  })

  it('leaves the gutter to the panel when it is a card inside one', () => {
    const { container } = render(
      <Loaded what="run" failed={null} value={null}>
        {() => null}
      </Loaded>,
    )

    // The settings panel already insets its column; a note inset a second time would sit 48px in.
    expect(container.firstChild).toBe(screen.getByText('Reading the run…'))
  })

  it('draws the content and nothing of its own once it has arrived', () => {
    render(
      <Loaded what="record" failed={null} value={{ rows: 3 }} header={<h1>bumps</h1>}>
        {(read) => <p>{read.rows} row(s)</p>}
      </Loaded>,
    )

    expect(screen.getByText('3 row(s)')).toBeTruthy()
    // The header is the page's own once there is a page; drawing this one as well would draw two.
    expect(screen.queryByText('bumps')).toBeNull()
    expect(screen.queryByText('Reading the record…')).toBeNull()
  })
})
