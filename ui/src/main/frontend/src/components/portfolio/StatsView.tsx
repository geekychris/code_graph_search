// Project stats: one repo's make-up (RepoStatsTab) and rollups across the
// portfolio (PortfolioStats). Every number comes from the same language × kind
// counts, so the views agree with each other (docs/STATS.md).

import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { getRepoStats, getStatsRollup } from '../../api/client'
import type { Counts, Dimension, Metric, StatsQuery } from '../../types/stats'
import { familyColor, languageColor } from './palette'

export const KIND_COLORS: Record<string, string> = {
  source: '#3b82f6', test: '#22c55e', config: '#a78bfa', build: '#f59e0b', ci: '#ec4899',
  docs: '#14b8a6', data: '#eab308', generated: '#64748b', asset: '#475569', other: '#94a3b8',
}
const KIND_ORDER = ['source', 'test', 'config', 'build', 'ci', 'docs', 'data', 'generated', 'asset', 'other']
const OTHER_COLOR = '#334155'

const fmt = (n: number) => n.toLocaleString()
const short = (n: number) => n >= 1e6 ? (n / 1e6).toFixed(n >= 1e7 ? 0 : 1) + 'M' : n >= 1e3 ? (n / 1e3).toFixed(n >= 1e4 ? 0 : 1) + 'k' : String(n)
const bytesFmt = (n: number) => n >= 1 << 30 ? (n / (1 << 30)).toFixed(1) + ' GB' : n >= 1 << 20 ? (n / (1 << 20)).toFixed(1) + ' MB' : (n / 1024).toFixed(0) + ' KB'
const metricValue = (c: Counts, m: Metric) => c[m] ?? 0
const metricLabel: Record<Metric, string> = { code: 'code lines', lines: 'all lines', files: 'files', comment: 'comment lines', blank: 'blank lines', bytes: 'bytes' }

const PALETTE = ['#3b82f6', '#22c55e', '#f59e0b', '#a78bfa', '#ec4899', '#14b8a6', '#eab308', '#f97316', '#06b6d4',
  '#84cc16', '#e879f9', '#fb7185', '#60a5fa', '#4ade80', '#fbbf24', '#c084fc', '#f472b6', '#2dd4bf', '#facc15', '#fb923c']
const GREYS: Record<string, string> = { '(other)': OTHER_COLOR, Other: '#475569', Binary: '#334155', '(no family)': '#64748b', '(no tags)': '#64748b' }

/**
 * Colours for one view's keys, all different: kinds and families keep their own
 * colours, a language keeps its usual colour while no other key has taken it,
 * and the rest get palette colours nobody uses yet.
 */
function colorScale(dim: Dimension, keys: string[]): (key: string) => string {
  const out = new Map<string, string>()
  const used = new Set<string>()
  for (const k of keys) {
    let c = GREYS[k]
    if (!c && dim === 'kind') c = KIND_COLORS[k]
    if (!c && dim === 'family') c = familyColor(k)
    if (!c && dim === 'language') { const l = languageColor(k); if (l !== '#64748b' && !used.has(l)) c = l }
    if (c) { out.set(k, c); used.add(c) }
  }
  let next = 0
  for (const k of keys) {
    if (out.has(k)) continue
    while (next < PALETTE.length && used.has(PALETTE[next])) next++
    const c = PALETTE[next % PALETTE.length] ?? PALETTE[out.size % PALETTE.length]
    out.set(k, c); used.add(c); next++
  }
  return (k: string) => out.get(k) ?? OTHER_COLOR
}

/** A colour for a key of a dimension, for lists that don't build a scale. */
function colorFor(dim: Dimension, key: string, i: number): string {
  if (GREYS[key]) return GREYS[key]
  if (dim === 'kind') return KIND_COLORS[key] ?? OTHER_COLOR
  if (dim === 'family') return familyColor(key)
  if (dim === 'language') { const c = languageColor(key); if (c !== '#64748b') return c }
  return PALETTE[i % PALETTE.length]
}

/** A horizontal bar split into parts. */
function StackBar({ parts, total, height = 10 }: { parts: { key: string; value: number; color: string }[]; total: number; height?: number }) {
  return (
    <div className="flex w-full rounded overflow-hidden bg-slate-800" style={{ height }}>
      {parts.filter((p) => p.value > 0).map((p) => (
        <div key={p.key} title={`${p.key}: ${fmt(p.value)} (${total ? Math.round((p.value / total) * 100) : 0}%)`}
          style={{ width: `${total ? (p.value / total) * 100 : 0}%`, background: p.color }} />
      ))}
    </div>
  )
}

function Legend({ items }: { items: { key: string; color: string; value?: string }[] }) {
  return (
    <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-400">
      {items.map((i) => (
        <span key={i.key} className="flex items-center gap-1.5">
          <span className="w-2.5 h-2.5 rounded-sm" style={{ background: i.color }} />{i.key}{i.value && <span className="text-slate-500">{i.value}</span>}
        </span>
      ))}
    </div>
  )
}

function Stat({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="bg-slate-800/60 border border-slate-700/60 rounded-lg px-4 py-3 min-w-[120px]">
      <div className="text-xl font-semibold text-white tabular-nums">{value}</div>
      <div className="text-xs text-slate-400">{label}</div>
      {sub && <div className="text-[11px] text-slate-500 mt-0.5">{sub}</div>}
    </div>
  )
}

function Totals({ c, repos }: { c: Counts; repos?: number }) {
  const ratio = (a: number, b: number) => (b ? Math.round((a / b) * 100) + '%' : '–')
  return (
    <div className="flex flex-wrap gap-3">
      {repos != null && <Stat label="repos" value={fmt(repos)} />}
      <Stat label="files" value={fmt(c.files)} sub={bytesFmt(c.bytes)} />
      <Stat label="code lines" value={fmt(c.code)} />
      <Stat label="comment lines" value={fmt(c.comment)} sub={`${ratio(c.comment, c.code + c.comment)} of code + comments`} />
      <Stat label="blank lines" value={fmt(c.blank)} />
      <Stat label="all lines" value={fmt(c.lines)} />
    </div>
  )
}

function CountsTable({ rows, first, onRow, colorOf }: {
  rows: { key: string; counts: Counts; repos?: number }[]; first: string; onRow?: (key: string) => void; colorOf?: (key: string, i: number) => string
}) {
  const showRepos = rows.some((r) => r.repos != null)
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="text-xs text-slate-400 border-b border-slate-700">
          <th className="text-left py-1.5 pr-3 font-medium">{first}</th>
          {showRepos && <th className="text-right px-2 font-medium">repos</th>}
          <th className="text-right px-2 font-medium">files</th>
          <th className="text-right px-2 font-medium">code</th>
          <th className="text-right px-2 font-medium">comment</th>
          <th className="text-right px-2 font-medium">blank</th>
          <th className="text-right px-2 font-medium">lines</th>
          <th className="text-right pl-2 font-medium">size</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={r.key} className={`border-b border-slate-800 ${onRow ? 'hover:bg-slate-800/60 cursor-pointer' : ''}`} onClick={() => onRow?.(r.key)}>
            <td className="py-1.5 pr-3">
              <span className="inline-flex items-center gap-2">
                {colorOf && <span className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ background: colorOf(r.key, i) }} />}
                <span className={onRow ? 'text-blue-300' : 'text-slate-200'}>{r.key}</span>
              </span>
            </td>
            {showRepos && <td className="text-right px-2 tabular-nums text-slate-400">{r.repos ?? ''}</td>}
            <td className="text-right px-2 tabular-nums">{fmt(r.counts.files)}</td>
            <td className="text-right px-2 tabular-nums text-white">{fmt(r.counts.code)}</td>
            <td className="text-right px-2 tabular-nums text-slate-400">{fmt(r.counts.comment)}</td>
            <td className="text-right px-2 tabular-nums text-slate-400">{fmt(r.counts.blank)}</td>
            <td className="text-right px-2 tabular-nums">{fmt(r.counts.lines)}</td>
            <td className="text-right pl-2 tabular-nums text-slate-500">{bytesFmt(r.counts.bytes)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

const sorted = (m: Record<string, Counts>, metric: Metric) =>
  Object.entries(m).map(([key, counts]) => ({ key, counts })).sort((a, b) => metricValue(b.counts, metric) - metricValue(a.counts, metric) || a.key.localeCompare(b.key))

// ---------------------------------------------------------------------------

/** A repo's Stats tab. */
export function RepoStatsTab({ repoId }: { repoId: string }) {
  const { data, error, isLoading } = useQuery({ queryKey: ['repo-stats', repoId], queryFn: () => getRepoStats(repoId), staleTime: 5 * 60_000, retry: false })
  if (isLoading) return <div className="p-6 text-slate-400">Counting…</div>
  if (error || !data) return <div className="p-6 text-slate-400">No stats yet for {repoId}. They're counted when the repo is next indexed.</div>

  const langs = sorted(data.byLanguage, 'code')
  const langColor = colorScale('language', langs.map((l) => l.key))
  const kinds = sorted(data.byKind, 'lines').sort((a, b) => KIND_ORDER.indexOf(a.key) - KIND_ORDER.indexOf(b.key))
  const testRatio = (data.byKind.test?.code ?? 0) / Math.max(1, (data.byKind.source?.code ?? 0))
  const codeTotal = langs.reduce((n, l) => n + l.counts.code, 0)

  return (
    <div className="h-full overflow-y-auto p-6 space-y-6">
      <div className="flex items-baseline justify-between">
        <Totals c={data.totals} />
        <div className="text-xs text-slate-500 text-right">
          at <span className="font-mono">{data.commit?.slice(0, 8)}</span>, counted {new Date(data.computedAt).toLocaleString()}
          <br />{fmt(data.excluded)} vendored or build-output files not counted
        </div>
      </div>

      <section className="space-y-2">
        <h3 className="text-sm font-semibold text-white">Languages <span className="text-slate-500 font-normal">by code lines</span></h3>
        <StackBar total={codeTotal} parts={langs.slice(0, 12).map((l) => ({ key: l.key, value: l.counts.code, color: langColor(l.key) }))} height={14} />
        <Legend items={langs.filter((l) => l.counts.code > 0).slice(0, 12).map((l) => ({ key: l.key, color: langColor(l.key), value: codeTotal ? Math.round((l.counts.code / codeTotal) * 100) + '%' : '' }))} />
      </section>

      <section className="space-y-2">
        <h3 className="text-sm font-semibold text-white">Kinds <span className="text-slate-500 font-normal">by all lines · test/source code ratio {testRatio.toFixed(2)}</span></h3>
        <StackBar total={data.totals.lines} parts={kinds.map((k) => ({ key: k.key, value: k.counts.lines, color: KIND_COLORS[k.key] ?? OTHER_COLOR }))} height={14} />
        <CountsTable rows={kinds} first="kind" colorOf={(k) => KIND_COLORS[k] ?? OTHER_COLOR} />
      </section>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        <section className="space-y-2">
          <h3 className="text-sm font-semibold text-white">Languages</h3>
          <CountsTable rows={langs} first="language" colorOf={langColor} />
        </section>
        <section className="space-y-2">
          <h3 className="text-sm font-semibold text-white">Top-level directories</h3>
          <CountsTable rows={Object.entries(data.byDir ?? {}).map(([key, counts]) => ({ key, counts }))} first="directory" />
        </section>
      </div>

      {data.largest?.length > 0 && (
        <section className="space-y-2">
          <h3 className="text-sm font-semibold text-white">Largest source and test files</h3>
          <table className="w-full text-sm">
            <tbody>
              {data.largest.map((f) => (
                <tr key={f.path} className="border-b border-slate-800">
                  <td className="py-1 font-mono text-xs text-slate-300">{f.path}</td>
                  <td className="text-xs text-slate-500 px-2">{f.language}</td>
                  <td className="text-xs px-2" style={{ color: KIND_COLORS[f.kind] }}>{f.kind}</td>
                  <td className="text-right tabular-nums">{fmt(f.lines)} lines</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </div>
  )
}

// ---------------------------------------------------------------------------

const DIMS: { id: Dimension; label: string }[] = [
  { id: 'family', label: 'Family' }, { id: 'repo', label: 'Repo' }, { id: 'language', label: 'Language' },
  { id: 'kind', label: 'Kind' }, { id: 'visibility', label: 'Visibility' }, { id: 'tag', label: 'Tag' }, { id: 'none', label: 'Everything' },
]
const METRICS: Metric[] = ['code', 'lines', 'files', 'comment', 'blank', 'bytes']
const FILTERS: { key: keyof StatsQuery; dim: Dimension }[] = [
  { key: 'family', dim: 'family' }, { key: 'language', dim: 'language' }, { key: 'kind', dim: 'kind' },
  { key: 'repo', dim: 'repo' }, { key: 'tag', dim: 'tag' }, { key: 'visibility', dim: 'visibility' },
]

/** The Stats page: rollups across every repo the caller can see. */
export function PortfolioStats({ onOpenRepo }: { onOpenRepo: (id: string) => void }) {
  const [q, setQ] = useState<StatsQuery>({ group: 'family', breakdown: 'kind', metric: 'code' })
  const { data, error, isLoading } = useQuery({ queryKey: ['stats-rollup', q], queryFn: () => getStatsRollup(q), staleTime: 60_000, placeholderData: (p) => p })
  const set = (patch: Partial<StatsQuery>) => setQ((old) => ({ ...old, ...patch }))
  const filters = FILTERS.filter((f) => q[f.key])
  const partColor = useMemo(() => colorScale(q.breakdown, uniqueParts(data?.groups ?? [], 1000)), [data, q.breakdown])
  const maxGroup = useMemo(() => Math.max(1, ...(data?.groups ?? []).map((g) => metricValue(g.counts, q.metric))), [data, q.metric])

  // clicking a group drills in: filter by it and show its repos; a repo opens its page
  const drill = (key: string) => {
    if (q.group === 'repo') return onOpenRepo(key)
    if (q.group === 'none' || key.startsWith('(')) return
    const breakdown: Dimension = q.group === 'language' ? 'kind' : q.breakdown === q.group ? 'none' : q.breakdown
    set({ [q.group]: key, group: 'repo', breakdown } as Partial<StatsQuery>)
  }

  return (
    <div className="h-full overflow-y-auto p-6 space-y-5">
      <div>
        <h2 className="text-xl font-semibold text-white">Project stats</h2>
        <p className="text-sm text-slate-400">Files and lines by language and kind across every repo you can see, counted at each repo's indexed commit. Group, break down and filter; click a group to drill in.</p>
      </div>

      <div className="flex flex-wrap items-center gap-3 text-sm">
        <Select label="Group by" value={q.group} options={DIMS} onChange={(v) => set({ group: v as Dimension })} />
        <Select label="Break down by" value={q.breakdown} options={[{ id: 'none', label: 'Nothing' }, ...DIMS.filter((d) => d.id !== 'none')]} onChange={(v) => set({ breakdown: v as Dimension })} />
        <Select label="Measure" value={q.metric} options={METRICS.map((m) => ({ id: m, label: metricLabel[m] }))} onChange={(v) => set({ metric: v as Metric })} />
        {filters.map((f) => (
          <span key={f.key} className="flex items-center gap-1 px-2 py-1 rounded bg-blue-500/15 text-blue-200 text-xs">
            {f.key}: {String(q[f.key])}
            <button className="text-blue-300 hover:text-white ml-1" onClick={() => set({ [f.key]: undefined } as Partial<StatsQuery>)} aria-label={`Remove ${f.key} filter`}>×</button>
          </span>
        ))}
        {filters.length > 0 && <button className="text-xs text-slate-400 hover:text-white" onClick={() => setQ({ group: 'family', breakdown: 'kind', metric: q.metric })}>Reset</button>}
      </div>

      {error && <div className="text-sm text-red-400">{String(error)}</div>}
      {isLoading && !data && <div className="text-slate-400">Loading…</div>}
      {data && (
        <>
          <Totals c={data.totals} repos={data.repos} />
          {data.repos === 0 && <p className="text-sm text-slate-400">No repo has stats yet: they're counted as repos are indexed.</p>}
          <section className="space-y-1.5">
            {data.groups.slice(0, 60).map((g, gi) => {
              const v = metricValue(g.counts, q.metric)
              const parts = q.breakdown === 'none'
                ? [{ key: g.key, value: v, color: colorFor(q.group, g.key, gi) }]
                : g.breakdown.map((p) => ({ key: p.key, value: metricValue(p.counts, q.metric), color: partColor(p.key) }))
              return (
                <div key={g.key} className="grid grid-cols-[220px_1fr_90px] items-center gap-3 text-sm">
                  <button onClick={() => drill(g.key)} className="text-left truncate text-blue-300 hover:text-white" title={`${g.key}: ${g.repos} repo(s)`}>
                    {g.key} <span className="text-slate-500 text-xs">{q.group !== 'repo' ? `${g.repos}` : ''}</span>
                  </button>
                  <div style={{ width: `${Math.max(2, (v / maxGroup) * 100)}%` }}>
                    <StackBar total={v} parts={parts} height={12} />
                  </div>
                  <div className="text-right tabular-nums text-slate-300">{q.metric === 'bytes' ? bytesFmt(v) : short(v)}</div>
                </div>
              )
            })}
            {data.groups.length > 60 && <p className="text-xs text-slate-500">{data.groups.length - 60} more in the table below</p>}
          </section>
          {q.breakdown !== 'none' && (
            <Legend items={uniqueParts(data.groups).map((k) => ({ key: k, color: partColor(k) }))} />
          )}
          <section className="pt-2">
            <CountsTable rows={data.groups.map((g) => ({ key: g.key, counts: g.counts, repos: g.repos }))}
              first={DIMS.find((d) => d.id === q.group)?.label.toLowerCase() ?? q.group}
              onRow={q.group === 'none' ? undefined : drill} colorOf={(k, i) => colorFor(q.group, k, i)} />
          </section>
        </>
      )}
    </div>
  )
}

function uniqueParts(groups: { breakdown: { key: string }[] }[], max = 20): string[] {
  const seen: string[] = []
  for (const g of groups) for (const p of g.breakdown) if (!seen.includes(p.key)) seen.push(p.key)
  return seen.slice(0, max)
}

function Select({ label, value, options, onChange }: { label: string; value: string; options: { id: string; label: string }[]; onChange: (v: string) => void }) {
  return (
    <label className="flex items-center gap-2 text-slate-400">
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)}
        className="bg-slate-800 border border-slate-700 rounded px-2 py-1 text-slate-200">
        {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </select>
    </label>
  )
}
