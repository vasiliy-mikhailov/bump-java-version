import type { Package } from '@bjv/types'
import { EmptyNote } from '../primitives/EmptyNote'

export type PackageTableProps = { packages: Package[] }

/**
 * WHAT THE BUMP MOVED, WITH THE MODULE THAT RESOLVED IT.
 *
 * The Java's table showed package, version and CVE count and NOT the module, so a six-module project
 * rendered `io.netty:netty-codec-http 4.1.79.Final` six identical times and looked like a rendering
 * bug. It was not: the scan is per module and the rows were six different facts wearing the same
 * label. The column is the whole fix.
 *
 * Rows are collapsed where every module agrees, which is the common case, and the module count is
 * shown instead. A reader who needs the per-module detail can still see it in the count; a reader
 * who does not is no longer reading the same line six times.
 */
export function PackageTable({ packages }: PackageTableProps) {
  if (packages.length === 0) {
    return <EmptyNote>No dependency scan for this bump.</EmptyNote>
  }
  const rows = collapse(packages)
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12.5px' }}>
        <thead>
          <tr>
            <th style={th}>package</th>
            <th style={th}>modules</th>
            <th style={th}>before</th>
            <th style={th}>after</th>
            <th style={{ ...th, textAlign: 'right' }}>CVEs</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.key} style={{ borderTop: '1px solid var(--border-soft)' }}>
              <td style={{ ...td, fontFamily: 'ui-monospace, Menlo, monospace' }}>{r.name}</td>
              <td style={{ ...td, color: 'var(--text-tertiary)' }}>
                {r.modules === 1 ? r.module : `${r.modules} modules`}
              </td>
              <td style={td}>{r.versionBefore ?? '—'}</td>
              <td style={td}>
                {r.versionAfter == null ? (
                  '—'
                ) : r.versionAfter === r.versionBefore ? (
                  <span style={{ color: 'var(--text-tertiary)' }}>unchanged</span>
                ) : (
                  r.versionAfter
                )}
              </td>
              <td style={{ ...td, textAlign: 'right' }}>
                <span style={{ color: 'var(--cve-remaining)' }}>{r.cvesBefore}</span>
                {' → '}
                <span
                  style={{
                    color:
                      r.cvesAfter < r.cvesBefore
                        ? 'var(--cve-cleared)'
                        : r.cvesAfter > r.cvesBefore
                          ? 'var(--cve-introduced)'
                          : 'var(--cve-remaining)',
                  }}
                >
                  {r.cvesAfter}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** The sibling's `th`: 11px, uppercase, letterspaced, on a strong rule. */
const th = {
  textAlign: 'left',
  color: 'var(--text-tertiary)',
  fontWeight: 500,
  fontSize: '11px',
  textTransform: 'uppercase',
  letterSpacing: '.06em',
  padding: '9px 24px',
  borderBottom: '1px solid var(--border-strong)',
} as const
const td = { padding: '8px 24px', verticalAlign: 'top' } as const

type Row = Package & { key: string; modules: number }

/** One row per (package, version pair); the modules that agree are counted, not repeated. */
export function collapse(packages: Package[]): Row[] {
  const by = new Map<string, Row>()
  for (const p of packages) {
    const key = `${p.name}|${p.versionBefore ?? ''}|${p.versionAfter ?? ''}`
    const seen = by.get(key)
    if (seen === undefined) {
      by.set(key, { ...p, key, modules: 1 })
    } else {
      seen.modules += 1
    }
  }
  return [...by.values()].sort((a, b) => b.cvesBefore - a.cvesBefore || a.name.localeCompare(b.name))
}
