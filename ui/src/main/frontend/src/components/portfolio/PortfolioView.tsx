import React, { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search, Lock, Boxes, GitFork, LayoutGrid, Sparkles, ArrowRight, Layers } from 'lucide-react'
import { getPortfolioOverview, searchConcepts } from '../../api/client'
import type { PortfolioRepo } from '../../types/knowledge'
import CyGraph, { BASE_STYLE } from './CyGraph'
import { familyColor, seedFamilies, lifecycleColor, languageColor, kindColor, formatCount } from './palette'

type Mode = 'map' | 'deps' | 'cards'

interface Props {
  onOpenRepo: (id: string) => void
  onOpenConcept: (slug: string) => void
}

export default function PortfolioView({ onOpenRepo, onOpenConcept }: Props) {
  const { data, isLoading, error } = useQuery({ queryKey: ['portfolio-overview'], queryFn: getPortfolioOverview, staleTime: 60_000 })
  const [mode, setMode] = useState<Mode>('map')
  const [family, setFamily] = useState<string | null>(null)
  const [lifecycle, setLifecycle] = useState('')
  const [language, setLanguage] = useState('')
  const [filter, setFilter] = useState('')
  const [question, setQuestion] = useState('')
  const [hovered, setHovered] = useState<PortfolioRepo | null>(null)
  const [selected, setSelected] = useState<PortfolioRepo | null>(null)
  const [showRelated, setShowRelated] = useState(false)

  const concepts = useQuery({
    queryKey: ['concepts', question],
    queryFn: () => searchConcepts(question, '', 12),
    enabled: question.trim().length > 1,
  })

  const families = useMemo(() => {
    if (!data) return []
    seedFamilies(Object.keys(data.families))
    return Object.entries(data.families).sort((a, b) => b[1].length - a[1].length)
  }, [data])

  const repos = useMemo(() => {
    if (!data) return []
    const f = filter.toLowerCase()
    return data.repos.filter((r) =>
      (!family || (r.family || 'unsorted') === family) &&
      (!lifecycle || r.lifecycle === lifecycle) &&
      (!language || (r.languages || []).some((l) => l.toLowerCase() === language)) &&
      (!f || r.id.toLowerCase().includes(f) || (r.purpose || '').toLowerCase().includes(f) ||
        r.tags.some((t) => t.toLowerCase().includes(f))))
  }, [data, family, lifecycle, language, filter])

  const repoById = useMemo(() => new Map((data?.repos || []).map((r) => [r.id, r])), [data])

  const graph = useMemo(() => {
    if (!data) return { elements: [], layout: { name: 'grid' } as any }
    const ids = new Set(repos.map((r) => r.id))
    const deps = data.edges.filter((e) => ids.has(e.from) && ids.has(e.to))
    const els: any[] = []
    if (mode === 'deps') {
      const connected = new Set(deps.flatMap((e) => [e.from, e.to]))
      repos.filter((r) => connected.has(r.id)).forEach((r) => els.push(repoNode(r, false)))
      deps.forEach((e) => els.push({ data: { id: `d:${e.from}>${e.to}`, source: e.from, target: e.to, width: 1.5 } }))
      return { elements: els, layout: { name: 'dagre', rankDir: 'TB', nodeSep: 18, rankSep: 70, animate: false } as any }
    }
    const fams = new Set(repos.map((r) => r.family || 'unsorted'))
    fams.forEach((f) => els.push({ data: { id: `fam:${f}`, label: f, color: familyColor(f), family: true } }))
    repos.forEach((r) => els.push(repoNode(r, true)))
    deps.forEach((e) => els.push({ data: { id: `d:${e.from}>${e.to}`, source: e.from, target: e.to, width: 1.5 } }))
    if (showRelated) {
      const seen = new Set<string>()
      repos.forEach((r) => r.related.forEach((o) => {
        if (!ids.has(o)) return
        const k = [r.id, o].sort().join('~')
        if (seen.has(k)) return
        seen.add(k)
        els.push({ data: { id: `r:${k}`, source: r.id, target: o, width: 0.6 }, classes: 'related' })
      }))
    }
    return {
      elements: els,
      layout: { name: 'cose-bilkent', animate: false, nodeRepulsion: 6000, idealEdgeLength: 60, nestingFactor: 0.6,
        gravity: 0.4, gravityCompound: 1.4, tile: true, tilingPaddingVertical: 12, tilingPaddingHorizontal: 12,
        randomize: true, numIter: 2500 } as any,
    }
  }, [data, repos, mode, showRelated])

  const stylesheet = useMemo(() => [
    ...BASE_STYLE,
    { selector: 'node[?family]', style: {
      'background-color': 'data(color)', 'background-opacity': 0.08, 'border-width': 1, 'border-color': 'data(color)',
      'border-opacity': 0.5, label: 'data(label)', 'text-valign': 'top', 'text-halign': 'center', 'font-size': 18,
      'font-weight': 600, color: 'data(color)', 'text-margin-y': -6, shape: 'round-rectangle', padding: 14,
    } },
    { selector: 'node[size]', style: { width: 'data(size)', height: 'data(size)', 'border-width': 2.5, 'border-color': 'data(ring)' } },
    { selector: 'node[?private]', style: { shape: 'round-rectangle' } },
    { selector: 'edge.related', style: { 'line-style': 'dotted', 'target-arrow-shape': 'none', 'line-color': '#334155' } },
  ] as any, [])

  if (isLoading) return <Centered>Loading the portfolio…</Centered>
  if (error || !data) return <Centered>Couldn't load the portfolio: {String((error as Error)?.message || '')}</Centered>

  const panel = hovered || selected
  const langs = Object.entries(data.languages).sort((a, b) => b[1] - a[1])
  const langTotal = langs.reduce((s, [, n]) => s + n, 0) || 1

  return (
    <div className="h-full flex flex-col overflow-hidden">
      {/* Headline numbers and the concept search */}
      <div className="px-6 pt-5 pb-4 border-b border-slate-800 shrink-0">
        <div className="flex items-end gap-10 flex-wrap">
          <Stat label="repositories" value={data.stats.repos} />
          <Stat label="code elements" value={formatCount(data.stats.elements)} />
          <Stat label="families" value={families.length} />
          <Stat label="dependency links" value={data.edges.length} />
          <Stat label="described by sessions" value={`${data.stats.described}`} />
          <div className="flex-1 min-w-[220px]">
            <div className="text-[11px] uppercase tracking-wide text-slate-500 mb-1">languages (repos)</div>
            <div className="flex h-2.5 rounded overflow-hidden">
              {langs.map(([l, n]) => (
                <div key={l} title={`${l}: ${n}`} style={{ width: `${(n / langTotal) * 100}%`, background: languageColor(l) }}
                  className="cursor-pointer hover:opacity-80" onClick={() => setLanguage(language === l ? '' : l)} />
              ))}
            </div>
            <div className="flex gap-3 mt-1 text-[11px] text-slate-400 flex-wrap">
              {langs.slice(0, 7).map(([l, n]) => (
                <button key={l} onClick={() => setLanguage(language === l ? '' : l)}
                  className={`flex items-center gap-1 ${language === l ? 'text-white' : ''}`}>
                  <span className="w-2 h-2 rounded-full" style={{ background: languageColor(l) }} />{l} {n}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="mt-4 relative max-w-3xl">
          <Sparkles size={16} className="absolute left-3 top-3 text-violet-400" />
          <input value={question} onChange={(e) => setQuestion(e.target.value)}
            placeholder="What already exists for…  (vector search, JWT auth, scheduler, MCP server, ZooKeeper)"
            className="w-full bg-slate-800/80 border border-slate-700 rounded-lg pl-9 pr-3 py-2.5 text-sm placeholder-slate-500 focus:outline-none focus:border-violet-500" />
          {question.trim().length > 1 && (
            <div className="absolute z-20 left-0 right-0 mt-1 bg-slate-900 border border-slate-700 rounded-lg shadow-2xl max-h-96 overflow-y-auto">
              {(concepts.data || []).length === 0 && !concepts.isFetching && (
                <div className="px-4 py-3 text-sm text-slate-500">No concepts match. Try the code Search tab.</div>
              )}
              {(concepts.data || []).map((c) => (
                <button key={c.slug} onClick={() => { setQuestion(''); onOpenConcept(c.slug) }}
                  className="w-full text-left px-4 py-2.5 hover:bg-slate-800 border-b border-slate-800 last:border-0">
                  <div className="flex items-center gap-2">
                    <span className="w-2 h-2 rounded-full" style={{ background: kindColor(c.kind) }} />
                    <span className="text-sm text-white">{c.name}</span>
                    <span className="text-[11px] text-slate-500">{c.kind}</span>
                    <span className="ml-auto text-[11px] text-slate-400">{c.repos.length} repo{c.repos.length === 1 ? '' : 's'}</span>
                  </div>
                  <div className="text-xs text-slate-400 mt-0.5 truncate">
                    {c.summary || c.repos.slice(0, 6).map((u) => u.repoId).join(', ')}
                  </div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Controls */}
      <div className="flex items-center gap-2 px-6 py-2 border-b border-slate-800 shrink-0 flex-wrap">
        <div className="flex bg-slate-800 rounded-lg p-0.5">
          {([['map', 'Family map', <Boxes size={14} key="m" />], ['deps', 'Dependency layers', <Layers size={14} key="d" />],
            ['cards', 'Cards', <LayoutGrid size={14} key="c" />]] as const).map(([m, label, icon]) => (
            <button key={m} onClick={() => setMode(m as Mode)}
              className={`flex items-center gap-1.5 px-3 py-1 rounded text-xs ${mode === m ? 'bg-slate-600 text-white' : 'text-slate-400 hover:text-white'}`}>
              {icon}{label}
            </button>
          ))}
        </div>
        <div className="relative">
          <Search size={13} className="absolute left-2 top-2 text-slate-500" />
          <input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Filter repos"
            className="bg-slate-800 border border-slate-700 rounded pl-7 pr-2 py-1 text-xs w-44 focus:outline-none focus:border-blue-500" />
        </div>
        <select value={lifecycle} onChange={(e) => setLifecycle(e.target.value)}
          className="bg-slate-800 border border-slate-700 rounded px-2 py-1 text-xs">
          <option value="">Any status</option>
          {['active', 'maintained', 'experiment', 'dormant', 'abandoned', 'superseded'].map((s) => <option key={s}>{s}</option>)}
        </select>
        {mode === 'map' && (
          <label className="flex items-center gap-1.5 text-xs text-slate-400 ml-1">
            <input type="checkbox" checked={showRelated} onChange={(e) => setShowRelated(e.target.checked)} />
            related links
          </label>
        )}
        <div className="ml-auto text-xs text-slate-500">{repos.length} shown</div>
      </div>

      <div className="flex flex-1 overflow-hidden">
        {/* Family rail */}
        <div className="w-52 shrink-0 border-r border-slate-800 overflow-y-auto py-2">
          <button onClick={() => setFamily(null)}
            className={`w-full text-left px-4 py-1.5 text-xs ${!family ? 'text-white bg-slate-800' : 'text-slate-400 hover:text-white'}`}>
            All families
          </button>
          {families.map(([f, ids]) => (
            <button key={f} onClick={() => setFamily(family === f ? null : f)}
              className={`w-full text-left px-4 py-1.5 text-xs flex items-center gap-2 ${family === f ? 'text-white bg-slate-800' : 'text-slate-400 hover:text-white'}`}>
              <span className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ background: familyColor(f) }} />
              <span className="truncate">{f}</span>
              <span className="ml-auto text-slate-600">{ids.length}</span>
            </button>
          ))}
        </div>

        {/* Canvas or cards */}
        <div className="flex-1 overflow-hidden relative">
          {mode === 'cards' ? (
            <CardsView repos={repos} onOpenRepo={onOpenRepo} />
          ) : graph.elements.length === 0 ? (
            <Centered>{mode === 'deps' ? 'No dependency links among the repos shown.' : 'No repos match.'}</Centered>
          ) : (
            <CyGraph className="h-full" elements={graph.elements} stylesheet={stylesheet} layout={graph.layout}
              onTapNode={(id, d) => { if (!d.family) setSelected(repoById.get(id) || null) }}
              onDoubleTapNode={(id, d) => { if (d.family) setFamily(d.label); else onOpenRepo(id) }}
              onHover={(d) => setHovered(d && !d.family ? repoById.get(d.id) || null : null)} />
          )}
          {mode !== 'cards' && (
            <div className="absolute top-3 left-3 text-[11px] text-slate-500 bg-slate-900/80 rounded px-2 py-1 pointer-events-none">
              {mode === 'map' ? 'Size: code elements · ring: status · arrows: depends on · double-click to open'
                : 'Arrows point at what a repo builds on · double-click to open'}
            </div>
          )}
        </div>

        {/* Repo preview */}
        {mode !== 'cards' && (
          <div className="w-80 shrink-0 border-l border-slate-800 overflow-y-auto">
            {panel ? <RepoPreview repo={panel} onOpen={() => onOpenRepo(panel.id)} edges={data.edges} />
              : <div className="p-5 text-sm text-slate-500">Hover or click a repo to preview it. Double-click to open it.</div>}
          </div>
        )}
      </div>
    </div>
  )
}

function repoNode(r: PortfolioRepo, withParent: boolean) {
  const size = 10 + Math.min(46, Math.sqrt(r.elements) / 3)
  return {
    data: {
      id: r.id, label: r.id, size, color: familyColor(r.family), ring: lifecycleColor(r.lifecycle),
      private: r.visibility === 'PRIVATE', ...(withParent ? { parent: `fam:${r.family || 'unsorted'}` } : {}),
    },
  }
}

function RepoPreview({ repo, onOpen, edges }: { repo: PortfolioRepo; onOpen: () => void; edges: { from: string; to: string }[] }) {
  const dependsOn = edges.filter((e) => e.from === repo.id).map((e) => e.to)
  const usedBy = edges.filter((e) => e.to === repo.id).map((e) => e.from)
  return (
    <div className="p-5 space-y-3">
      <div>
        <div className="flex items-center gap-2">
          <span className="w-3 h-3 rounded-sm" style={{ background: familyColor(repo.family) }} />
          <span className="text-xs text-slate-400">{repo.family || 'unsorted'}</span>
          {repo.visibility === 'PRIVATE' && <Lock size={12} className="text-amber-400" />}
        </div>
        <div className="text-lg font-semibold text-white mt-1 break-all">{repo.id}</div>
        <div className="flex items-center gap-2 mt-1 text-xs">
          <span className="px-1.5 py-0.5 rounded" style={{ background: lifecycleColor(repo.lifecycle) + '33', color: lifecycleColor(repo.lifecycle) }}>
            {repo.lifecycle || 'unknown'}
          </span>
          {repo.maturity != null && <Maturity value={repo.maturity} />}
          {repo.category && <span className="text-slate-500">{repo.category}</span>}
        </div>
      </div>
      {repo.purpose && <p className="text-sm text-slate-300 leading-relaxed">{repo.purpose}</p>}
      <div className="grid grid-cols-3 gap-2 text-center">
        <Mini label="elements" value={formatCount(repo.elements)} />
        <Mini label="files" value={formatCount(repo.files)} />
        <Mini label="concepts" value={repo.concepts} />
      </div>
      {repo.tags.length > 0 && (
        <div className="flex flex-wrap gap-1">
          {repo.tags.map((t) => <span key={t} className="text-[11px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-300">{t}</span>)}
        </div>
      )}
      {dependsOn.length > 0 && <Links label="Builds on" ids={dependsOn} />}
      {usedBy.length > 0 && <Links label="Used by" ids={usedBy} />}
      <button onClick={onOpen}
        className="w-full flex items-center justify-center gap-2 bg-blue-600 hover:bg-blue-500 text-white text-sm rounded-lg py-2">
        Open repo <ArrowRight size={14} />
      </button>
    </div>
  )
}

function CardsView({ repos, onOpenRepo }: { repos: PortfolioRepo[]; onOpenRepo: (id: string) => void }) {
  const byFamily = useMemo(() => {
    const m = new Map<string, PortfolioRepo[]>()
    repos.forEach((r) => {
      const f = r.family || 'unsorted'
      if (!m.has(f)) m.set(f, [])
      m.get(f)!.push(r)
    })
    return [...m.entries()].sort((a, b) => b[1].length - a[1].length)
  }, [repos])
  return (
    <div className="h-full overflow-y-auto px-6 py-4 space-y-6">
      {byFamily.map(([f, list]) => (
        <div key={f}>
          <div className="flex items-center gap-2 mb-2">
            <span className="w-3 h-3 rounded-sm" style={{ background: familyColor(f) }} />
            <span className="text-sm font-semibold text-white">{f}</span>
            <span className="text-xs text-slate-500">{list.length}</span>
          </div>
          <div className="grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))' }}>
            {list.sort((a, b) => b.elements - a.elements).map((r) => (
              <button key={r.id} onClick={() => onOpenRepo(r.id)}
                className="text-left bg-slate-800/60 hover:bg-slate-800 border border-slate-700/60 hover:border-slate-600 rounded-lg p-3 transition-colors"
                style={{ borderLeft: `3px solid ${familyColor(r.family)}` }}>
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full shrink-0" style={{ background: lifecycleColor(r.lifecycle) }} title={r.lifecycle} />
                  <span className="text-sm text-white font-medium truncate">{r.id}</span>
                  {r.visibility === 'PRIVATE' && <Lock size={11} className="text-amber-400 shrink-0" />}
                  {r.described && <Sparkles size={11} className="text-violet-400 shrink-0" />}
                </div>
                <div className="text-xs text-slate-400 mt-1 line-clamp-2 min-h-[2rem]">{r.purpose || '—'}</div>
                <div className="flex items-center gap-2 mt-2 text-[11px] text-slate-500">
                  {(r.languages || []).filter((l) => !['markdown', 'yaml', 'json'].includes(l.toLowerCase())).slice(0, 3).map((l) => (
                    <span key={l} className="flex items-center gap-1">
                      <span className="w-1.5 h-1.5 rounded-full" style={{ background: languageColor(l) }} />{l.toLowerCase()}
                    </span>
                  ))}
                  <span className="ml-auto">{formatCount(r.elements)} el</span>
                </div>
              </button>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

function Links({ label, ids }: { label: string; ids: string[] }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wide text-slate-500 mb-1 flex items-center gap-1"><GitFork size={11} />{label}</div>
      <div className="flex flex-wrap gap-1">
        {ids.map((id) => <span key={id} className="text-[11px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-300">{id}</span>)}
      </div>
    </div>
  )
}

export function Maturity({ value }: { value: number }) {
  return (
    <span className="flex gap-0.5" title={`maturity ${value}/5`}>
      {[1, 2, 3, 4, 5].map((i) => <span key={i} className={`w-1.5 h-1.5 rounded-full ${i <= value ? 'bg-sky-400' : 'bg-slate-700'}`} />)}
    </span>
  )
}

function Stat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div className="text-2xl font-semibold text-white tabular-nums">{value}</div>
      <div className="text-[11px] uppercase tracking-wide text-slate-500">{label}</div>
    </div>
  )
}

function Mini({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div className="bg-slate-800/70 rounded py-1.5">
      <div className="text-sm text-white tabular-nums">{value}</div>
      <div className="text-[10px] text-slate-500">{label}</div>
    </div>
  )
}

export function Centered({ children }: { children: React.ReactNode }) {
  return <div className="h-full flex items-center justify-center text-sm text-slate-500 p-6 text-center">{children}</div>
}
