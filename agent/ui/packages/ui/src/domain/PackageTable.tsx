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
                {/* NOT MEASURED IS NOT ZERO. A green 0 here told a reader the dependency had been
                    cleaned up on bumps that were never scanned a second time at all. */}
                {r.cvesAfter === null ? (
                  <span style={{ color: 'var(--text-tertiary)' }} title="no after scan">
                    —
                  </span>
                ) : (
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
                )}
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
  return [...by.values()].sort(best)
}

/**
 * BEST OUTCOME FIRST, WORST LAST, which is not the same as most-vulnerable-first.
 *
 * This sorted by CVEs BEFORE, so the table was ordered by how bad the project used to be. On a
 * successful bump that puts the biggest win and the biggest remaining problem in the same place at
 * the top and sorts everything else by a number the bump has already made obsolete.
 *
 * The order now is what the bump DID:
 *
 *   1. how many CVEs it cleared, descending, so the wins lead and anything it made worse sinks
 *      below every row that changed nothing;
 *   2. then, among rows that cleared the same amount, how many are LEFT, descending, so that
 *      11 -> 11 sits above 0 -> 0 rather than being buried under a hundred clean dependencies;
 *   3. then the name, so the order is stable between renders.
 *
 * A row whose after was never measured cannot be scored, so it sorts as having cleared nothing and
 * keeps its place by what it carried.
 */
export function best(a: Row, b: Row): number {
  return cleared(b) - cleared(a) || left(b) - left(a) || a.name.localeCompare(b.name)
}

/** What the bump removed here, or 0 when there is no after to compare against. */
function cleared(r: Row): number {
  return r.cvesAfter === null ? 0 : r.cvesBefore - r.cvesAfter
}

/** What is still there, or what was there when nothing was measured after. */
function left(r: Row): number {
  return r.cvesAfter ?? r.cvesBefore
}
