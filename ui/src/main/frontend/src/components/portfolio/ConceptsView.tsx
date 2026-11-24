import React, { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search, Sparkles, Tag, Boxes } from 'lucide-react'
import { searchConcepts, getConcept, getPortfolioOverview } from '../../api/client'
import type { ConceptEntry } from '../../types/knowledge'
import CyGraph, { BASE_STYLE } from './CyGraph'
import { kindColor, familyColor } from './palette'
import { Centered } from './PortfolioView'

interface Props {
  slug: string | null
  onSlug: (slug: string | null) => void
  onOpenRepo: (id: string) => void
  onOpenElement: (id: string) => void
}

const FILTERS = ['', 'capability', 'technique', 'pattern', 'api', 'infrastructure', 'domain', 'tag', 'family']

/** Concepts across the portfolio: a searchable list, and for one concept a drawing of where it lives. */
export default function ConceptsView({ slug, onSlug, onOpenRepo, onOpenElement }: Props) {
  const [q, setQ] = useState('')
  const [kind, setKind] = useState('')
  const list = useQuery({ queryKey: ['concepts', q, kind, 'list'], queryFn: () => searchConcepts(q, kind, 300), staleTime: 30_000 })

  return (
    <div className="h-full flex overflow-hidden">
      <div className="w-96 shrink-0 border-r border-slate-800 flex flex-col">
        <div className="p-4 border-b border-slate-800 space-y-2">
          <div className="relative">
            <Search size={14} className="absolute left-2.5 top-2.5 text-slate-500" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search concepts, techniques, infrastructure"
              className="w-full bg-slate-800 border border-slate-700 rounded-lg pl-8 pr-2 py-2 text-sm focus:outline-none focus:border-violet-500" />
          </div>
          <div className="flex flex-wrap gap-1">
            {FILTERS.map((f) => (
              <button key={f || 'all'} onClick={() => setKind(f)}
                className={`text-[11px] px-2 py-0.5 rounded-full border ${kind === f ? 'border-white text-white' : 'border-slate-700 text-slate-400 hover:text-white'}`}
                style={f && kind === f ? { borderColor: kindColor(f), color: kindColor(f) } : {}}>
                {f || 'all'}
              </button>
            ))}
          </div>
        </div>
        <div className="flex-1 overflow-y-auto">
          {(list.data || []).map((c) => (
            <button key={c.slug} onClick={() => onSlug(c.slug)}
              className={`w-full text-left px-4 py-2 border-b border-slate-800/70 hover:bg-slate-800/60 ${slug === c.slug ? 'bg-slate-800' : ''}`}>
              <div className="flex items-center gap-2">
                <SourceIcon source={c.source} kind={c.kind} />
                <span className="text-sm text-white truncate">{c.name}</span>
                <span className="ml-auto text-[11px] text-slate-500 shrink-0">{c.repos.length}</span>
              </div>
              {c.summary && <div className="text-[11px] text-slate-500 truncate pl-5">{c.summary}</div>}
            </button>
          ))}
          {list.data && list.data.length === 0 && <div className="p-4 text-sm text-slate-500">No concepts match.</div>}
        </div>
      </div>
      <div className="flex-1 overflow-hidden">
        {slug ? <ConceptDetail slug={slug} onOpenRepo={onOpenRepo} onOpenElement={onOpenElement} />
          : <Centered>Pick a concept to see every repo that implements it. Concepts come from Claude sessions (anchored to code), repo-atlas tags and families.</Centered>}
      </div>
    </div>
  )
}

function SourceIcon({ source, kind }: { source: string; kind: string }) {
  if (source === 'session') return <Sparkles size={12} style={{ color: kindColor(kind) }} className="shrink-0" />
  if (source === 'family') return <Boxes size={12} className="text-fuchsia-400 shrink-0" />
  return <Tag size={12} className="text-slate-500 shrink-0" />
}

function ConceptDetail({ slug, onOpenRepo, onOpenElement }: { slug: string; onOpenRepo: (id: string) => void; onOpenElement: (id: string) => void }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['concept', slug], queryFn: () => getConcept(slug) })
  const portfolio = useQuery({ queryKey: ['portfolio-overview'], queryFn: getPortfolioOverview, staleTime: 60_000 })
  const families = useMemo(() => new Map((portfolio.data?.repos || []).map((r) => [r.id, r.family])), [portfolio.data])
  const graph = useMemo(() => (data ? drawing(data, (id) => families.get(id)) : null), [data, families])
  const stylesheet = useMemo(() => [...BASE_STYLE,
    { selector: 'node', style: { width: 'data(size)', height: 'data(size)' } },
    { selector: 'node.concept', style: { shape: 'star', 'font-size': 15, 'font-weight': 600 } },
    { selector: 'node.anchor', style: { shape: 'round-rectangle', 'font-size': 9, color: '#7dd3fc', 'background-color': '#0c4a6e' } },
    { selector: 'edge', style: { 'target-arrow-shape': 'none', 'line-color': '#334155' } },
  ] as any, [])

  if (isLoading) return <Centered>Loading…</Centered>
  if (error || !data) return <Centered>This concept isn't visible to you.</Centered>

  return (
    <div className="h-full flex flex-col">
      <div className="px-6 py-4 border-b border-slate-800">
        <div className="flex items-center gap-2">
          <span className="text-[11px] uppercase tracking-wide" style={{ color: kindColor(data.kind) }}>{data.kind}</span>
          <span className="text-[11px] text-slate-500">· {data.source === 'session' ? 'described by sessions' : data.source === 'tag' ? 'repo-atlas tag' : 'repo-atlas family'}</span>
        </div>
        <h1 className="text-xl font-semibold text-white mt-1">{data.name}</h1>
        {data.summary && <p className="text-sm text-slate-300 mt-1 max-w-3xl">{data.summary}</p>}
      </div>
      <div className="flex flex-1 overflow-hidden">
        <CyGraph className="flex-1" elements={graph!} stylesheet={stylesheet}
          layout={{ name: 'cose-bilkent', animate: false, idealEdgeLength: 90, nodeRepulsion: 8000, randomize: true } as any}
          onDoubleTapNode={(id, d) => { if (d.repo) onOpenRepo(d.repo); else if (d.element) onOpenElement(d.element) }} />
        <div className="w-[420px] border-l border-slate-800 overflow-y-auto p-4 space-y-3">
          <div className="text-[11px] uppercase tracking-wide text-slate-500">{data.repos.length} repo{data.repos.length === 1 ? '' : 's'}</div>
          {data.repos.map((u) => (
            <div key={u.repoId} className="bg-slate-800/50 rounded-lg p-3">
              <button onClick={() => onOpenRepo(u.repoId)} className="text-sm text-white font-medium hover:text-blue-300">{u.repoId}</button>
              {u.summary && <div className="text-xs text-slate-300 mt-1 leading-relaxed">{u.summary}</div>}
              {u.howToUse && <div className="text-xs text-emerald-300/90 mt-1.5"><span className="text-slate-500">How to use: </span>{u.howToUse}</div>}
              {u.anchors && u.anchors.length > 0 && (
                <div className="mt-2 flex flex-wrap gap-1">
                  {u.anchors.map((a, i) => (
                    <button key={i} disabled={!a.elementId} onClick={() => a.elementId && onOpenElement(a.elementId)} title={a.qualifiedName || a.path}
                      className={`text-[10px] px-1.5 py-0.5 rounded font-mono ${a.elementId ? 'bg-sky-500/10 text-sky-300 hover:bg-sky-500/20' : 'bg-slate-800 text-slate-500'}`}>
                      {a.label || (a.qualifiedName || a.path || '').split(/[./]/).pop()}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

/** The concept in the middle, the repos that have it around it, and their code anchors on the outside. */
function drawing(c: ConceptEntry, familyOf: (repo: string) => string | undefined) {
  const els: any[] = [{ data: { id: 'c', label: c.name, color: kindColor(c.kind), size: 46 }, classes: 'concept' }]
  c.repos.forEach((u) => {
    const rid = `r:${u.repoId}`
    els.push({ data: { id: rid, label: u.repoId, repo: u.repoId, color: familyColor(familyOf(u.repoId)), size: 26 } })
    els.push({ data: { id: `e:${rid}`, source: 'c', target: rid, width: 2 } })
    ;(u.anchors || []).slice(0, 8).forEach((a, i) => {
      const aid = `${rid}:a${i}`
      els.push({ data: { id: aid, label: a.label || (a.qualifiedName || a.path || '').split(/[./]/).pop(), element: a.elementId, size: 14, color: '#0c4a6e' }, classes: 'anchor' })
      els.push({ data: { id: `e:${aid}`, source: rid, target: aid, width: 1 } })
    })
  })
  return els
}
