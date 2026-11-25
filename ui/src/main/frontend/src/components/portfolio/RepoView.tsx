import React, { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft, Lock, Github, Sparkles, Box, Network, BookOpen, GitFork, Lightbulb, Info, BarChart3 } from 'lucide-react'
import { RepoStatsTab } from './StatsView'
import { getRepoOverview, getArchitecture, getRepoDeps } from '../../api/client'
import type { Concept, RepoOverview } from '../../types/knowledge'
import Markdown from './Markdown'
import Mermaid from './Mermaid'
import ArchitectureMap, { derivedDiagram } from './ArchitectureMap'
import CyGraph, { BASE_STYLE } from './CyGraph'
import { Centered, Maturity } from './PortfolioView'
import { familyColor, lifecycleColor, languageColor, kindColor, formatCount } from './palette'

export type RepoTab = 'overview' | 'architecture' | 'stats' | 'concepts' | 'dependencies' | 'dossier'

interface Props {
  repoId: string
  tab: RepoTab
  archPath: string
  onTab: (t: RepoTab) => void
  onArchPath: (p: string) => void
  onBack: () => void
  onOpenRepo: (id: string) => void
  onOpenConcept: (slug: string) => void
  onOpenElement: (id: string) => void
  onSearchFile: (repoId: string, path: string) => void
}

export default function RepoView(p: Props) {
  const { data, isLoading, error } = useQuery({ queryKey: ['repo-overview', p.repoId], queryFn: () => getRepoOverview(p.repoId) })

  if (isLoading) return <Centered>Loading {p.repoId}…</Centered>
  if (error || !data) return <Centered>Repository {p.repoId} isn't visible to you, or doesn't exist.</Centered>

  const card = data.card
  const k = data.knowledge
  const tabs: { id: RepoTab; label: string; icon: React.ReactNode; count?: number }[] = [
    { id: 'overview', label: 'Overview', icon: <Info size={14} /> },
    { id: 'architecture', label: 'Architecture', icon: <Network size={14} /> },
    { id: 'stats', label: 'Stats', icon: <BarChart3 size={14} /> },
    { id: 'concepts', label: 'Concepts', icon: <Lightbulb size={14} />, count: (k?.concepts?.length || 0) + (card?.tags?.length || 0) },
    { id: 'dependencies', label: 'Dependencies', icon: <GitFork size={14} />, count: (data.dependsOn?.length || 0) + (data.usedBy?.length || 0) },
    { id: 'dossier', label: 'Dossier', icon: <BookOpen size={14} /> },
  ]

  return (
    <div className="h-full flex flex-col overflow-hidden">
      {/* Header */}
      <div className="px-6 pt-4 pb-0 border-b border-slate-800 shrink-0" style={{ borderTop: `3px solid ${familyColor(card?.family)}` }}>
        <div className="flex items-center gap-2 text-xs text-slate-400">
          <button onClick={p.onBack} className="flex items-center gap-1 hover:text-white"><ArrowLeft size={13} />Portfolio</button>
          <span className="text-slate-600">/</span>
          <span className="w-2.5 h-2.5 rounded-sm" style={{ background: familyColor(card?.family) }} />
          <span>{card?.family || 'unsorted'}</span>
        </div>
        <div className="flex items-start gap-4 mt-2">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-3 flex-wrap">
              <h1 className="text-2xl font-semibold text-white">{p.repoId}</h1>
              {data.repo.visibility === 'PRIVATE' && <span className="flex items-center gap-1 text-[11px] px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-300"><Lock size={11} />private</span>}
              {card?.status && <span className="text-[11px] px-1.5 py-0.5 rounded" style={{ background: lifecycleColor(card.status) + '26', color: lifecycleColor(card.status) }}>{card.status}</span>}
              {card?.maturity != null && <Maturity value={card.maturity} />}
              {card?.category && <span className="text-xs text-slate-500">{card.category}</span>}
              {data.repo.htmlUrl && <a href={data.repo.htmlUrl} target="_blank" rel="noreferrer" className="text-slate-400 hover:text-white"><Github size={15} /></a>}
            </div>
            <p className="text-sm text-slate-300 mt-1.5 max-w-4xl">{card?.purpose || data.repo.description || 'No description yet.'}</p>
          </div>
          <div className="flex gap-5 text-right shrink-0">
            <HeaderStat label="elements" value={formatCount(data.repo.elementCount)} />
            <HeaderStat label="files" value={formatCount(data.repo.fileCount)} />
            <HeaderStat label="concepts" value={k?.concepts?.length || 0} />
          </div>
        </div>
        <div className="flex gap-1 mt-3">
          {tabs.map((t) => (
            <button key={t.id} onClick={() => p.onTab(t.id)}
              className={`flex items-center gap-1.5 px-3 py-2 text-sm border-b-2 -mb-px ${p.tab === t.id ? 'border-blue-500 text-white' : 'border-transparent text-slate-400 hover:text-white'}`}>
              {t.icon}{t.label}{t.count ? <span className="text-[10px] text-slate-500">{t.count}</span> : null}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-hidden">
        {p.tab === 'overview' && <OverviewTab data={data} {...p} />}
        {p.tab === 'architecture' && (
          <ArchitectureMap repoId={p.repoId} path={p.archPath} onPath={p.onArchPath} onOpenRepo={p.onOpenRepo}
            onOpenElement={p.onOpenElement} onSearchFile={(path) => p.onSearchFile(p.repoId, path)} />
        )}
        {p.tab === 'stats' && <RepoStatsTab repoId={p.repoId} />}
        {p.tab === 'concepts' && <ConceptsTab data={data} {...p} />}
        {p.tab === 'dependencies' && <DependenciesTab repoId={p.repoId} onOpenRepo={p.onOpenRepo} />}
        {p.tab === 'dossier' && <DossierTab data={data} />}
      </div>
    </div>
  )
}

function OverviewTab({ data, repoId, onOpenRepo, onOpenConcept, onOpenElement, onTab }: { data: RepoOverview } & Props) {
  const k = data.knowledge
  const card = data.card
  const arch = useQuery({ queryKey: ['architecture', repoId, ''], queryFn: () => getArchitecture(repoId, ''), staleTime: 5 * 60_000 })
  const derived = useMemo(() => (arch.data && arch.data.groups.length > 0 ? derivedDiagram(arch.data) : null), [arch.data])

  return (
    <div className="h-full overflow-y-auto">
      <div className="grid gap-6 p-6" style={{ gridTemplateColumns: 'minmax(0, 1fr) 320px' }}>
        <div className="space-y-6 min-w-0">
          <Section title={k?.diagram ? 'Concept drawing' : 'Structure drawing'}
            note={k?.diagram ? `by ${k.author}` : 'derived from the code graph · a session can replace it with a concept drawing'}>
            {k?.diagram ? <Mermaid source={k.diagram} className="py-2" />
              : derived ? <Mermaid source={derived} className="py-2" />
              : arch.isLoading ? <div className="text-sm text-slate-500">Reading the structure…</div>
              : <div className="text-sm text-slate-500">No parsed code to draw.</div>}
            <button onClick={() => onTab('architecture')} className="text-xs text-blue-400 hover:text-blue-300 mt-2">Explore the architecture →</button>
          </Section>

          {k?.overview ? (
            <Section title="Overview" note={`written by ${k.author}${k.commit ? ' at ' + k.commit.slice(0, 7) : ''}`}>
              <Markdown text={k.overview} />
            </Section>
          ) : (
            <Section title="Overview" note="from the repo atlas">
              {card?.sections?.Purpose || card?.sections?.Design
                ? <><Markdown text={card?.sections?.Purpose} /><Markdown text={card?.sections?.Design} className="mt-3" /></>
                : <div className="text-sm text-slate-500">No write-up yet.</div>}
              <NotDescribed />
            </Section>
          )}

          {k?.concepts && k.concepts.length > 0 && (
            <Section title="Concepts">
              <div className="grid gap-3" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))' }}>
                {k.concepts.map((c) => <ConceptCard key={c.slug} c={c} onOpenConcept={onOpenConcept} onOpenElement={onOpenElement} compact />)}
              </div>
            </Section>
          )}
        </div>

        <div className="space-y-5">
          {arch.data && arch.data.keyTypes.length > 0 && (
            <Side title="Load-bearing types">
              {arch.data.keyTypes.slice(0, 8).map((t) => (
                <button key={t.id} onClick={() => onOpenElement(t.id)} className="w-full text-left px-2 py-1 rounded hover:bg-slate-800">
                  <div className="flex items-center gap-2 text-xs"><Box size={11} className="text-sky-400" /><span className="text-white truncate">{t.name}</span>
                    <span className="ml-auto text-[10px] text-slate-500">{t.inbound}{t.crossRepoInbound ? ` +${t.crossRepoInbound}↗` : ''}</span></div>
                </button>
              ))}
            </Side>
          )}
          {(data.dependsOn?.length || 0) > 0 && <Side title="Builds on"><Chips ids={data.dependsOn!} onClick={onOpenRepo} /></Side>}
          {(data.usedBy?.length || 0) > 0 && <Side title="Used by"><Chips ids={data.usedBy!} onClick={onOpenRepo} /></Side>}
          {(card?.related?.length || 0) > 0 && <Side title="Related"><Chips ids={card!.related!} onClick={onOpenRepo} /></Side>}
          {(card?.tags?.length || 0) > 0 && (
            <Side title="Tags">
              <div className="flex flex-wrap gap-1">
                {card!.tags!.map((t) => (
                  <button key={t} onClick={() => onOpenConcept(slug(t))} className="text-[11px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 hover:bg-slate-700">{t}</button>
                ))}
              </div>
            </Side>
          )}
          {(card?.tech?.length || 0) > 0 && (
            <Side title="Technology"><div className="text-xs text-slate-400 leading-relaxed">{card!.tech!.join(' · ')}</div></Side>
          )}
          {data.repo.languages && (
            <Side title="Languages">
              <div className="flex flex-wrap gap-2 text-xs text-slate-400">
                {data.repo.languages.map((l) => <span key={l} className="flex items-center gap-1"><span className="w-2 h-2 rounded-full" style={{ background: languageColor(l) }} />{l.toLowerCase()}</span>)}
              </div>
            </Side>
          )}
        </div>
      </div>
    </div>
  )
}

function ConceptsTab({ data, onOpenConcept, onOpenElement }: { data: RepoOverview } & Props) {
  const k = data.knowledge
  return (
    <div className="h-full overflow-y-auto p-6 space-y-6">
      {k?.concepts && k.concepts.length > 0 ? (
        <div className="grid gap-4" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))' }}>
          {k.concepts.map((c) => <ConceptCard key={c.slug} c={c} onOpenConcept={onOpenConcept} onOpenElement={onOpenElement} />)}
        </div>
      ) : <NotDescribed />}
      {(data.card?.tags?.length || 0) > 0 && (
        <Section title="Atlas tags" note="each tag is a concept shared across repos">
          <div className="flex flex-wrap gap-2">
            {data.card!.tags!.map((t) => (
              <button key={t} onClick={() => onOpenConcept(slug(t))} className="text-xs px-2 py-1 rounded bg-slate-800 text-slate-200 hover:bg-slate-700">{t}</button>
            ))}
          </div>
        </Section>
      )}
    </div>
  )
}

export function ConceptCard({ c, onOpenConcept, onOpenElement, compact }: {
  c: Concept; onOpenConcept: (s: string) => void; onOpenElement: (id: string) => void; compact?: boolean
}) {
  return (
    <div className="bg-slate-800/50 border border-slate-700/60 rounded-lg p-3" style={{ borderLeft: `3px solid ${kindColor(c.kind)}` }}>
      <div className="flex items-center gap-2">
        <button onClick={() => onOpenConcept(c.slug)} className="text-sm font-medium text-white hover:text-blue-300 text-left">{c.name}</button>
        <span className="text-[10px] uppercase tracking-wide" style={{ color: kindColor(c.kind) }}>{c.kind}</span>
      </div>
      {c.summary && <div className="text-xs text-slate-300 mt-1 leading-relaxed">{c.summary}</div>}
      {!compact && c.howToUse && <div className="text-xs text-emerald-300/90 mt-2"><span className="text-slate-500">How to use: </span>{c.howToUse}</div>}
      {!compact && c.details && <Markdown text={c.details} className="text-xs mt-2" />}
      {c.anchors && c.anchors.length > 0 && (
        <div className="mt-2 flex flex-wrap gap-1">
          {c.anchors.slice(0, compact ? 3 : 12).map((a, i) => (
            <button key={i} disabled={!a.elementId} onClick={() => a.elementId && onOpenElement(a.elementId)}
              title={a.qualifiedName || a.path}
              className={`text-[10px] px-1.5 py-0.5 rounded font-mono ${a.elementId ? 'bg-sky-500/10 text-sky-300 hover:bg-sky-500/20' : 'bg-slate-800 text-slate-500'}`}>
              {a.label || (a.qualifiedName || a.path || '').split(/[./]/).pop()}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function DependenciesTab({ repoId, onOpenRepo }: { repoId: string; onOpenRepo: (id: string) => void }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['repo-deps', repoId], queryFn: () => getRepoDeps(repoId) })
  const graph = useMemo(() => {
    if (!data) return null
    const els: any[] = [{ data: { id: repoId, label: repoId, color: '#3b82f6', size: 34 }, classes: 'me' }]
    const seen = new Set([repoId])
    const add = (id: string) => { if (!seen.has(id)) { seen.add(id); els.push({ data: { id, label: id, color: '#64748b', size: 22 } }) } }
    data.dependencies.forEach((e) => { add(e.toRepo); els.push({ data: { id: `o:${e.toRepo}:${e.coordinate}`, source: repoId, target: e.toRepo, width: 1.5, label: e.usedVersion || '' } }) })
    data.dependents.forEach((e) => { add(e.fromRepo); els.push({ data: { id: `i:${e.fromRepo}:${e.coordinate}`, source: e.fromRepo, target: repoId, width: 1.5, label: e.usedVersion || '' } }) })
    return els
  }, [data, repoId])
  const stylesheet = useMemo(() => [...BASE_STYLE,
    { selector: 'node', style: { width: 'data(size)', height: 'data(size)' } },
    { selector: 'edge', style: { label: 'data(label)', 'font-size': 9, color: '#94a3b8' } },
  ] as any, [])

  if (isLoading) return <Centered>Loading dependencies…</Centered>
  if (error || !data) return <Centered>No build manifests found for this repo.</Centered>
  if (data.dependencies.length + data.dependents.length === 0) {
    return <Centered>{repoId} doesn't use, and isn't used by, other repos here{data.produces.length ? ` (it produces ${data.produces.join(', ')})` : ''}.</Centered>
  }
  return (
    <div className="h-full flex">
      <CyGraph className="flex-1" elements={graph!} stylesheet={stylesheet}
        layout={{ name: 'dagre', rankDir: 'TB', nodeSep: 30, rankSep: 80, animate: false } as any}
        onDoubleTapNode={(id) => id !== repoId && onOpenRepo(id)} />
      <div className="w-96 border-l border-slate-800 overflow-y-auto p-4 space-y-4 text-xs">
        {data.produces.length > 0 && <Side title="Produces"><div className="font-mono text-slate-300 space-y-0.5">{data.produces.map((c) => <div key={c}>{c}</div>)}</div></Side>}
        <Side title={`Builds on (${data.dependencies.length})`}>
          {data.dependencies.map((e) => (
            <button key={e.toRepo + e.coordinate} onClick={() => onOpenRepo(e.toRepo)} className="w-full text-left py-1 hover:text-white text-slate-300">
              <span className="text-white">{e.toRepo}</span> <span className="text-slate-500 font-mono">{e.coordinate} {e.usedVersion}</span>
            </button>
          ))}
        </Side>
        <Side title={`Used by (${data.dependents.length})`}>
          {data.dependents.map((e) => (
            <button key={e.fromRepo + e.coordinate} onClick={() => onOpenRepo(e.fromRepo)} className="w-full text-left py-1 hover:text-white text-slate-300">
              <span className="text-white">{e.fromRepo}</span> <span className="text-slate-500 font-mono">{e.usedVersion}</span>
            </button>
          ))}
        </Side>
      </div>
    </div>
  )
}

function DossierTab({ data }: { data: RepoOverview }) {
  const sections = data.card?.sections
  if (!sections || Object.keys(sections).length === 0) return <Centered>No repo-atlas dossier for this repo.</Centered>
  return (
    <div className="h-full overflow-y-auto">
      <div className="max-w-4xl p-6 space-y-6">
        <div className="text-xs text-slate-500">From the repo atlas{data.card?.analyzed ? `, analysed ${data.card.analyzed}` : ''}{data.card?.analyzedCommit ? ` at ${data.card.analyzedCommit}` : ''}.</div>
        {Object.entries(sections).map(([title, text]) => (
          <Section key={title} title={title}><Markdown text={text} /></Section>
        ))}
      </div>
    </div>
  )
}

function NotDescribed() {
  return (
    <div className="mt-3 flex gap-2 items-start text-xs text-slate-400 bg-violet-500/5 border border-violet-500/20 rounded-lg p-3">
      <Sparkles size={14} className="text-violet-400 shrink-0 mt-0.5" />
      <div>No Claude session has described this repo's concepts yet. A session with an admin token can claim it over MCP
        (<code className="text-violet-300">claim_concept_work</code>, then <code className="text-violet-300">submit_concepts</code>) to add an overview,
        a concept drawing and concepts anchored to the code.</div>
    </div>
  )
}

function Section({ title, note, children }: { title: string; note?: string; children: React.ReactNode }) {
  return (
    <div className="bg-slate-900 border border-slate-800 rounded-xl p-5">
      <div className="flex items-baseline gap-3 mb-3">
        <h2 className="text-sm font-semibold text-white">{title}</h2>
        {note && <span className="text-[11px] text-slate-500">{note}</span>}
      </div>
      {children}
    </div>
  )
}

function Side({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-[11px] uppercase tracking-wide text-slate-500 mb-1.5">{title}</div>
      {children}
    </div>
  )
}

function Chips({ ids, onClick }: { ids: string[]; onClick: (id: string) => void }) {
  return (
    <div className="flex flex-wrap gap-1">
      {ids.map((id) => <button key={id} onClick={() => onClick(id)} className="text-[11px] px-1.5 py-0.5 rounded bg-slate-800 text-slate-300 hover:bg-slate-700 hover:text-white">{id}</button>)}
    </div>
  )
}

function HeaderStat({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div className="text-lg text-white tabular-nums">{value}</div>
      <div className="text-[10px] uppercase tracking-wide text-slate-500">{label}</div>
    </div>
  )
}

export function slug(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-+|-+$)/g, '')
}
