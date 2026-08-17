import { describe, expect, it } from 'vitest'
import type { AgentPrompt } from '@bjv/types'
import { lanesOf, platformOf, stemOf } from './lanes'

const agent = (name: string): AgentPrompt =>
  ({
    name,
    role: 'planner',
    stage: 'before-pins',
    within: 'module',
    loop: '',
    repeats: '',
    reads: 'enables',
    pins: 15,
    description: '',
    builtIn: '',
    edited: false,
    prompt: '',
  }) as unknown as AgentPrompt

describe('lanesOf', () => {
  it('puts each platform in its own lane, in first-appearance order', () => {
    const { platforms, rows } = lanesOf([
      agent('before-pins-planner@spring-boot'),
      agent('before-pins-planner@quarkus'),
      agent('before-pins-planner@adhoc'),
      agent('before-pins-doer@spring-boot'),
      agent('before-pins-doer@quarkus'),
      agent('before-pins-doer@adhoc'),
    ])

    expect(platforms).toEqual(['spring-boot', 'quarkus', 'adhoc'])
    expect(rows.map((r) => r.stem)).toEqual(['before-pins-planner', 'before-pins-doer'])
    expect(rows[0]?.cells.map((c) => c.prompt?.name)).toEqual([
      'before-pins-planner@spring-boot',
      'before-pins-planner@quarkus',
      'before-pins-planner@adhoc',
    ])
  })

  it('leaves a hole rather than shifting when a platform has no agent for a stem', () => {
    // THE WHOLE REASON THIS IS A LOOKUP. Under the grid this replaces, a missing card pulled the
    // next one into its place, so every card after it sat under the wrong heading and the page
    // gave no sign. A hole is a bug a reader can see.
    const { platforms, rows } = lanesOf([
      agent('bump-doer@spring-boot'),
      agent('bump-doer@adhoc'),
      agent('after-pins-doer@spring-boot'),
      agent('after-pins-doer@quarkus'),
      agent('after-pins-doer@adhoc'),
    ])

    expect(platforms).toEqual(['spring-boot', 'adhoc', 'quarkus'])
    const bump = rows.find((r) => r.stem === 'bump-doer')
    expect(bump?.cells.map((c) => c.prompt?.name ?? null)).toEqual([
      'bump-doer@spring-boot',
      'bump-doer@adhoc',
      null,
    ])
  })

  it('reports no lanes for agents that exist once', () => {
    // The platform stage decides the platform, so it cannot be keyed by one. Its block draws as
    // the ordinary grid, and this is how the caller is told to.
    const { platforms, rows } = lanesOf([agent('platform-planner'), agent('platform-doer')])
    expect(platforms).toEqual([])
    expect(rows).toEqual([])
  })

  it('reports no lanes for a mixed block, because there is no honest column for the odd one', () => {
    const { platforms } = lanesOf([agent('before-pins-doer@spring-boot'), agent('survey-doer')])
    expect(platforms).toEqual([])
  })

  it('splits a name into stem and platform', () => {
    expect(stemOf('before-pins-planner@spring-boot')).toBe('before-pins-planner')
    expect(platformOf('before-pins-planner@spring-boot')).toBe('spring-boot')
    expect(stemOf('survey-doer')).toBe('survey-doer')
    expect(platformOf('survey-doer')).toBe('')
  })
})
