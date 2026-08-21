import type { Package } from '@bjv/types'
import { DataTable, EmptyNote, type Column } from 'ratchet-ui/components'
import { MONO } from '../primitives/table'

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
 *
 * BOTH TABLES ON THIS SITE MOVED TO `DataTable` IN ONE COMMIT, and that is not tidiness. The guard
 * that keeps the corpus and this table set alike, `tables.test.tsx`, works by comparing the insets
 * of two RENDERED tables. Converting one and leaving the other keeps that test passing while the two
 * drift through different code paths, which is the one arrangement worse than the drift it was
 * written to catch.
 */
export function PackageTable({ packages }: PackageTableProps) {
  const columns: Column<Row>[] = [
    { head: 'package', cellStyle: { fontFamily: MONO }, cell: (r) => r.name },
    {
      head: 'modules',
      cellStyle: { color: 'var(--text-tertiary)' },
      cell: (r) => (r.modules === 1 ? r.module : `${r.modules} modules`),
    },
    { head: 'before', cell: (r) => r.versionBefore ?? '—' },
    {
      head: 'after',
      cell: (r) =>
        r.versionAfter == null ? (
          '—'
        ) : r.versionAfter === r.versionBefore ? (
          <span style={{ color: 'var(--text-tertiary)' }}>unchanged</span>
        ) : (
          r.versionAfter
        ),
    },
    {
      head: 'CVEs',
      align: 'right',
      cell: (r) => (
        <>
          <span style={{ color: 'var(--cve-remaining)' }}>{r.cvesBefore}</span>
          {' → '}
          {/* NOT MEASURED IS NOT ZERO. A green 0 here told a reader the dependency had been cleaned
              up on bumps that were never scanned a second time at all. */}
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
        </>
      ),
    },
  ]

  return (
    <DataTable
      rows={collapse(packages)}
      columns={columns}
      rowKey={(r) => r.key}
      empty={<EmptyNote>No dependency scan for this bump.</EmptyNote>}
    />
  )
}

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
 *
 * THE SORT STAYS HERE rather than becoming a prop on the shared shell. The sibling's index table has
 * a written decision NOT to sort, because its order is the run's plan and sorting by state groups
 * everything nobody has reached at one end, which looks like progress. Order belongs to whoever owns
 * the rows.
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
