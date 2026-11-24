import React, { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronRight, FolderTree, FileCode2, ExternalLink, Box } from 'lucide-react'
import { getArchitecture } from '../../api/client'
import type { Architecture, ArchGroup } from '../../types/knowledge'
import CyGraph, { BASE_STYLE } from './CyGraph'
import { languageColor, formatCount } from './palette'
import { Centered } from './PortfolioView'

interface Props {
  repoId: string
  path: string
  onPath: (path: string) => void
  onOpenRepo: (id: string) => void
  onOpenElement: (id: string) => void
  onSearchFile: (path: string) => void
}

const KIND_COLORS: Record<string, string> = {
  CALLS: '#f97316', EXTENDS: '#3b82f6', IMPLEMENTS: '#06b6d4', IMPORTS: '#22c55e', INSTANTIATES: '#eab308',
  USES_TYPE: '#a78bfa', OVERRIDES: '#a855f7', DEPENDS_ON: '#ef4444',
}

/** The dominant language of a group, for its colour. */
function mainLanguage(g: ArchGroup) {
  const e = Object.entries(g.languages || {}).sort((a, b) => b[1] - a[1])[0]
  return e ? e[0] : ''
}

/**
 * A repo's structure as a graph of its modules, packages or directories, with the
 * references between them. Double-click a group to go inside it; files open in search.
 */
export default function ArchitectureMap({ repoId, path, onPath, onOpenRepo, onOpenElement, onSearchFile }: Props) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['architecture', repoId, path],
    queryFn: () => getArchitecture(repoId, path),
    staleTime: 5 * 60_000,
  })
  const [showExternal, setShowExternal] = useState(true)
  const [hover, setHover] = useState<any>(null)

  const graph = useMemo(() => (data ? toElements(data, showExternal) : null), [data, showExternal])

  const stylesheet = useMemo(() => [
    ...BASE_STYLE,
    { selector: 'node', style: {
      shape: 'round-rectangle', width: 'data(w)', height: 'data(h)', 'text-valign': 'center', 'text-halign': 'center',
      'font-size': 13, 'text-wrap': 'wrap', 'text-max-width': 'data(tw)', 'background-color': '#1e293b',
      'border-width': 2, 'border-color': 'data(color)', color: '#f1f5f9', 'text-outline-width': 0,
      'min-zoomed-font-size': 7,
    } },
    { selector: 'node[?dir]', style: { 'background-color': '#172033', 'border-width': 3 } },
    { selector: 'node[?ext]', style: { shape: 'barrel', 'background-color': '#334155', 'border-width': 1, 'border-style': 'dashed', 'border-color': '#94a3b8', color: '#cbd5e1' } },
    { selector: 'node[?outside]', style: { shape: 'ellipse', 'background-color': '#1e293b', 'border-width': 1, 'border-style': 'dashed', 'border-color': '#64748b', color: '#94a3b8' } },
    { selector: 'edge', style: { label: 'data(label)', 'font-size': 9, color: '#94a3b8', 'text-background-color': '#0f172a',
      'text-background-opacity': 0.8, 'text-background-padding': '1px', 'line-color': 'data(color)', 'target-arrow-color': 'data(color)' } },
  ] as any, [])

  if (isLoading) return <Centered>Reading the structure of {repoId} from the code graph… (the first visit to a big repo can take a few seconds)</Centered>
  if (error || !data) return <Centered>Couldn't build the architecture: {String((error as Error)?.message || '')}</Centered>
  if (data.groups.length === 0) return <Centered>No parsed code in this repo (only languages with a parser appear here: Java, Go, Rust, TypeScript, JavaScript, C and C++).</Centered>

  const crumbs = ['', ...data.breadcrumbs]
  const groupById = new Map(data.groups.map((g) => [g.id, g]))
  const hovered = hover ? groupById.get(hover.id) : null

  return (
    <div className="h-full flex flex-col">
      <div className="flex items-center gap-1 px-4 py-2 border-b border-slate-800 text-xs flex-wrap">
        <FolderTree size={13} className="text-slate-500 mr-1" />
        {crumbs.map((c, i) => (
          <React.Fragment key={c || 'root'}>
            {i > 0 && <ChevronRight size={12} className="text-slate-600" />}
            <button onClick={() => onPath(c)}
              className={`px-1.5 py-0.5 rounded ${c === data.path ? 'text-white bg-slate-800' : 'text-slate-400 hover:text-white'}`}>
              {i === 0 ? repoId : c.split('/').pop()}
            </button>
          </React.Fragment>
        ))}
        <label className="ml-auto flex items-center gap-1.5 text-slate-400">
          <input type="checkbox" checked={showExternal} onChange={(e) => setShowExternal(e.target.checked)} /> other repos
        </label>
      </div>
      <div className="flex flex-1 overflow-hidden">
        <div className="flex-1 relative">
          <CyGraph className="absolute inset-0" elements={graph!.elements} stylesheet={stylesheet} layout={graph!.layout}
            onHover={setHover}
            onDoubleTapNode={(id, d) => {
              if (d.ext) onOpenRepo(d.repo)
              else if (d.outside) onPath(data.breadcrumbs.length > 1 ? data.breadcrumbs[data.breadcrumbs.length - 2] : '')
              else if (d.dir) onPath(id)
              else if (d.file) onSearchFile(d.file)
            }} />
          <div className="absolute top-3 left-3 text-[11px] text-slate-500 bg-slate-900/80 rounded px-2 py-1 pointer-events-none">
            Box size: code elements · colour: language · arrows: references · double-click a box to go inside
          </div>
          <div className="absolute bottom-3 left-3 flex gap-3 text-[10px] text-slate-400 bg-slate-900/80 rounded px-2 py-1">
            {Object.entries(KIND_COLORS).slice(0, 6).map(([k, c]) => (
              <span key={k} className="flex items-center gap-1"><span className="w-3 h-0.5" style={{ background: c }} />{k.toLowerCase()}</span>
            ))}
          </div>
        </div>
        <div className="w-80 shrink-0 border-l border-slate-800 overflow-y-auto p-4 space-y-5">
          {hovered ? (
            <div>
              <div className="text-sm font-semibold text-white break-all">{hovered.label}{hovered.hasChildren ? '/' : ''}</div>
              <div className="text-xs text-slate-400 mt-1">
                {hovered.files} files · {formatCount(hovered.elements)} elements · {hovered.types} types
              </div>
              <div className="text-xs text-slate-400">references in {hovered.inbound} · out {hovered.outbound}</div>
            </div>
          ) : (
            <div className="text-xs text-slate-500">
              {data.groups.length} parts here. Hover one for its numbers; the most-referenced types are listed below.
            </div>
          )}
          {data.keyTypes.length > 0 && (
            <div>
              <div className="text-[11px] uppercase tracking-wide text-slate-500 mb-2">Load-bearing types</div>
              <div className="space-y-1">
                {data.keyTypes.map((t) => (
                  <button key={t.id} onClick={() => onOpenElement(t.id)}
                    className="w-full text-left px-2 py-1.5 rounded hover:bg-slate-800 group">
                    <div className="flex items-center gap-2">
                      <Box size={12} className="text-sky-400 shrink-0" />
                      <span className="text-xs text-white truncate">{t.name}</span>
                      <span className="ml-auto text-[10px] text-slate-500 shrink-0">{t.inbound}{t.crossRepoInbound > 0 ? ` +${t.crossRepoInbound}↗` : ''}</span>
                    </div>
                    <div className="text-[10px] text-slate-500 truncate pl-5">{t.qualifiedName}</div>
                  </button>
                ))}
              </div>
              <div className="text-[10px] text-slate-600 mt-1">count = references from elsewhere in the repo; ↗ from other repos</div>
            </div>
          )}
          {data.external.length > 0 && (
            <div>
              <div className="text-[11px] uppercase tracking-wide text-slate-500 mb-2">Calls into other repos</div>
              {data.external.map((x) => (
                <button key={x.repoId} onClick={() => onOpenRepo(x.repoId)}
                  className="w-full text-left px-2 py-1.5 rounded hover:bg-slate-800">
                  <div className="flex items-center gap-2 text-xs text-white">
                    <ExternalLink size={11} className="text-slate-500" />{x.repoId}
                    <span className="ml-auto text-[10px] text-slate-500">{x.weight}</span>
                  </div>
                  <div className="text-[10px] text-slate-500 truncate pl-5">{x.targets.map((t) => t.split('.').pop()).join(', ')}</div>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function toElements(a: Architecture, showExternal: boolean) {
  const max = Math.max(1, ...a.groups.map((g) => g.elements))
  const els: any[] = []
  for (const g of a.groups) {
    const outside = g.id === '..'
    const scale = Math.sqrt(g.elements / max)
    const w = outside ? 110 : 90 + scale * 130
    const label = outside ? 'rest of repo' : `${g.label}${g.hasChildren ? '/' : ''}\n${g.types} types · ${formatCount(g.elements)}`
    els.push({ data: {
      id: g.id, label, w, h: outside ? 50 : 36 + scale * 50, tw: w - 10,
      color: outside ? '#1e293b' : languageColor(mainLanguage(g)),
      dir: g.hasChildren, outside, file: !g.hasChildren && !outside && !g.id.endsWith('…other') ? g.id : undefined,
    } })
  }
  const maxW = Math.max(1, ...a.edges.map((e) => e.weight))
  // Only the stronger links: a level with dozens of parts is unreadable with all of them
  const shown = [...a.edges].sort((x, y) => y.weight - x.weight)
    .filter((e, i) => i < 12 || (i < 60 && e.weight >= Math.max(2, maxW * 0.03)))
  for (const e of shown) {
    const main = Object.entries(e.kinds).sort((x, y) => y[1] - x[1])[0]?.[0] || 'CALLS'
    els.push({ data: {
      id: `e:${e.from}>${e.to}`, source: e.from, target: e.to, width: 1 + 6 * Math.sqrt(e.weight / maxW),
      color: KIND_COLORS[main] || '#64748b', label: e.weight > maxW * 0.15 ? String(e.weight) : '',
    } })
  }
  if (showExternal) {
    const extMax = Math.max(1, ...a.external.map((x) => x.weight))
    a.external.slice(0, 12).forEach((x) => {
      const id = `ext:${x.repoId}`
      els.push({ data: { id, label: x.repoId, ext: true, repo: x.repoId, w: 120, h: 40, tw: 110, color: '#334155' } })
      Object.entries(x.fromGroups || {}).forEach(([g, w]) => {
        els.push({ data: { id: `x:${g}>${x.repoId}`, source: g, target: id, width: 1 + 4 * Math.sqrt(w / extMax),
          color: '#94a3b8', label: w > extMax * 0.15 ? String(w) : '' } })
      })
    })
  }
  return {
    elements: els,
    layout: (a.edges.length === 0
      ? { name: 'grid', avoidOverlap: true, padding: 30 }
      : a.groups.length > 14
        ? { name: 'cose-bilkent', animate: false, idealEdgeLength: 140, nodeRepulsion: 12000, edgeElasticity: 0.3,
            nodeDimensionsIncludeLabels: true, randomize: true, numIter: 3000 }
        : { name: 'dagre', rankDir: 'LR', nodeSep: 30, rankSep: 110, edgeSep: 10, animate: false }) as any,
  }
}

/** A Mermaid drawing of one architecture level, for repos no session has drawn yet. */
export function derivedDiagram(a: Architecture): string {
  const groups = a.groups.filter((g) => g.id !== '..' && !g.id.endsWith('…other'))
    .sort((x, y) => y.elements - x.elements).slice(0, 12)
  const ids = new Map(groups.map((g, i) => [g.id, `g${i}`]))
  const esc = (s: string) => s.replace(/["<>[\]{}|]/g, '')
  const lines = ['flowchart LR',
    '  classDef part fill:#1e293b,stroke:#3b82f6,stroke-width:1.5px,color:#e2e8f0',
    '  classDef ext fill:#0f172a,stroke:#94a3b8,stroke-dasharray:4 3,color:#cbd5e1']
  groups.forEach((g) => lines.push(`  ${ids.get(g.id)}["<b>${esc(g.label)}</b><br/>${g.types} types"]:::part`))
  const edges = a.edges.filter((e) => ids.has(e.from) && ids.has(e.to)).sort((x, y) => y.weight - x.weight)
  const strongest = edges[0]?.weight || 1
  edges.filter((e) => e.weight >= Math.max(2, strongest * 0.05)).slice(0, 18)
    .forEach((e) => lines.push(`  ${ids.get(e.from)} -->|${e.weight}| ${ids.get(e.to)}`))
  a.external.slice(0, 5).forEach((x, i) => {
    lines.push(`  x${i}(["${esc(x.repoId)}"]):::ext`)
    Object.entries(x.fromGroups || {}).filter(([g]) => ids.has(g)).sort((p, q) => q[1] - p[1]).slice(0, 2)
      .forEach(([g, w]) => lines.push(`  ${ids.get(g)} -.->|${w}| x${i}`))
  })
  return lines.join('\n')
}

export function FileIcon() {
  return <FileCode2 size={12} />
}
