import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import CytoscapeComponent from 'react-cytoscapejs'
import cytoscape from 'cytoscape'
// @ts-ignore
import dagre from 'cytoscape-dagre'
// @ts-ignore
import coseBilkent from 'cytoscape-cose-bilkent'
import {
  GitBranch, Search, Play, BarChart3, Route, Waypoints, Users,
  Maximize2, Target, ZoomIn, ZoomOut, Info,
} from 'lucide-react'
import {
  searchCode, findShortestPath, findAllShortestPaths, findAllPaths, getSimilarity, getElement,
} from '../api/client'
import type { Element, Edge, EdgeType, EdgeDirection, PathResult, SimilarityResult } from '../types'
import { getElementTypeColor, getElementTypeBgClass, getElementTypeLabel } from '../utils/elementColors'

try { cytoscape.use(dagre) } catch (_) {}
try { cytoscape.use(coseBilkent) } catch (_) {}

const ALL_EDGE_TYPES: EdgeType[] = [
  'CONTAINS', 'CALLS', 'EXTENDS', 'IMPLEMENTS', 'OVERRIDES',
  'IMPORTS', 'USES_TYPE', 'DEPENDS_ON', 'DOCUMENTS', 'ANNOTATES',
]

const EDGE_COLORS: Record<string, string> = {
  CALLS: '#f97316', EXTENDS: '#3b82f6', IMPLEMENTS: '#06b6d4',
  CONTAINS: '#64748b', OVERRIDES: '#a855f7', DOCUMENTS: '#94a3b8',
  IMPORTS: '#22c55e', ANNOTATES: '#eab308', DEPENDS_ON: '#ef4444',
  USES_TYPE: '#ec4899',
}

const ALGORITHMS = [
  { id: 'shortest', label: 'Shortest Path', icon: <Route size={13} />, desc: 'Single shortest connection via BFS' },
  { id: 'all-shortest', label: 'All Shortest', icon: <Waypoints size={13} />, desc: 'All paths of minimum length' },
  { id: 'all-paths', label: 'All Paths', icon: <GitBranch size={13} />, desc: 'Every path up to max depth (DFS)' },
  { id: 'similarity', label: 'Similarity', icon: <Users size={13} />, desc: 'Common neighbors, Jaccard & Adamic-Adar' },
] as const

type AlgorithmId = typeof ALGORITHMS[number]['id']

interface Props {
  onSelectElement: (element: Element) => void
  externalElementA?: Element | null
  externalElementB?: Element | null
}

// ---- Element Picker with search-ahead ----

function ElementPicker({ label, value, onChange, placeholder, externalName }: {
  label: string
  value: string
  onChange: (id: string, el?: Element) => void
  placeholder?: string
  externalName?: string
}) {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)
  const [selectedName, setSelectedName] = useState(externalName || '')
  const inputRef = useRef<HTMLInputElement>(null)

  // Sync external name when it changes
  useEffect(() => {
    if (externalName) setSelectedName(externalName)
  }, [externalName])

  const { data } = useQuery({
    queryKey: ['picker-search', query],
    queryFn: () => searchCode({ q: query, limit: 8 }),
    enabled: query.length >= 2,
    staleTime: 30_000,
  })

  const handleSelect = useCallback((el: Element) => {
    onChange(el.id, el)
    setSelectedName(el.qualifiedName || el.name)
    setQuery('')
    setOpen(false)
  }, [onChange])

  const handleInputChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value
    setQuery(val)
    setOpen(val.length >= 2)
    // Allow direct ID input
    onChange(val)
    setSelectedName('')
  }, [onChange])

  const handleClear = useCallback(() => {
    onChange('')
    setSelectedName('')
    setQuery('')
  }, [onChange])

  return (
    <div className="relative">
      <label className="text-[10px] text-slate-500 uppercase tracking-wider font-medium">{label}</label>
      <div className="flex items-center gap-1 mt-0.5">
        <div className="relative flex-1">
          <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2 text-slate-500" />
          <input
            ref={inputRef}
            value={selectedName || value}
            onChange={handleInputChange}
            onFocus={() => { if (query.length >= 2) setOpen(true) }}
            onBlur={() => setTimeout(() => setOpen(false), 200)}
            placeholder={placeholder || 'Search or paste element ID...'}
            className="w-full pl-7 pr-2 py-1.5 bg-slate-800 border border-slate-700 rounded text-xs text-slate-300 placeholder-slate-600 focus:outline-none focus:border-blue-500 font-mono"
          />
        </div>
        {value && (
          <button onClick={handleClear} className="p-1 text-slate-600 hover:text-slate-400">
            <span className="text-xs">&times;</span>
          </button>
        )}
      </div>
      {open && data && data.items.length > 0 && (
        <div className="absolute z-50 mt-1 w-full bg-slate-800 border border-slate-600 rounded-lg shadow-xl max-h-64 overflow-y-auto">
          {data.items.map((el) => (
            <button
              key={el.id}
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => handleSelect(el)}
              className="w-full text-left px-3 py-2 hover:bg-slate-700 transition-colors border-b border-slate-700/50 last:border-0"
            >
              <div className="flex items-center gap-1.5">
                <span className={`px-1 py-0.5 rounded text-[9px] border ${getElementTypeBgClass(el.elementType)}`}>
                  {getElementTypeLabel(el.elementType)}
                </span>
                <span className="text-xs text-slate-300 truncate">{el.name}</span>
              </div>
              <div className="text-[10px] text-slate-500 mt-0.5 truncate font-mono">{el.qualifiedName}</div>
              {el.filePath && (
                <div className="text-[10px] text-slate-600 truncate">{el.filePath}:{el.lineStart}</div>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ---- Cytoscape helpers ----

function getNodeShape(type: string): string {
  if (['METHOD', 'FUNCTION', 'CONSTRUCTOR'].includes(type)) return 'rectangle'
  if (['FIELD', 'PROPERTY', 'ENUM_CONSTANT'].includes(type)) return 'diamond'
  return 'ellipse'
}

function buildCyElements(
  paths: Element[][],
  pathEdges: Edge[][],
  highlightIds?: Set<string>,
  elementA?: string,
  elementB?: string,
): cytoscape.ElementDefinition[] {
  const nodeMap = new Map<string, Element>()
  const edgeSet = new Map<string, Edge>()

  for (const path of paths) {
    for (const el of path) nodeMap.set(el.id, el)
  }
  for (const edges of pathEdges) {
    for (const e of edges) edgeSet.set(e.id || `${e.fromId}_${e.edgeType}_${e.toId}`, e)
  }

  const nodes: cytoscape.ElementDefinition[] = Array.from(nodeMap.values()).map((n) => ({
    data: {
      id: n.id,
      label: n.name.length > 25 ? n.name.slice(0, 23) + '...' : n.name,
      fullLabel: n.qualifiedName || n.name,
      elementType: n.elementType,
      color: getElementTypeColor(n.elementType),
      shape: getNodeShape(n.elementType),
      isEndpoint: n.id === elementA || n.id === elementB,
      isHighlighted: highlightIds?.has(n.id) || false,
    },
  }))

  const edges: cytoscape.ElementDefinition[] = Array.from(edgeSet.values())
    .filter((e) => nodeMap.has(e.fromId) && nodeMap.has(e.toId))
    .map((e) => ({
      data: {
        id: e.id || `${e.fromId}_${e.edgeType}_${e.toId}`,
        source: e.fromId,
        target: e.toId,
        edgeType: e.edgeType,
        color: EDGE_COLORS[e.edgeType] || '#64748b',
        label: e.edgeType,
      },
    }))

  return [...nodes, ...edges]
}

function buildSimilarityCyElements(
  result: SimilarityResult,
): cytoscape.ElementDefinition[] {
  const nodes: cytoscape.ElementDefinition[] = []
  const edges: cytoscape.ElementDefinition[] = []

  if (result.elementA) {
    nodes.push({
      data: {
        id: result.elementA.id,
        label: result.elementA.name,
        fullLabel: result.elementA.qualifiedName || result.elementA.name,
        elementType: result.elementA.elementType,
        color: getElementTypeColor(result.elementA.elementType),
        shape: getNodeShape(result.elementA.elementType),
        isEndpoint: true,
        isHighlighted: false,
      },
    })
  }
  if (result.elementB) {
    nodes.push({
      data: {
        id: result.elementB.id,
        label: result.elementB.name,
        fullLabel: result.elementB.qualifiedName || result.elementB.name,
        elementType: result.elementB.elementType,
        color: getElementTypeColor(result.elementB.elementType),
        shape: getNodeShape(result.elementB.elementType),
        isEndpoint: true,
        isHighlighted: false,
      },
    })
  }

  for (const cn of result.commonNeighbors) {
    nodes.push({
      data: {
        id: cn.id,
        label: cn.name.length > 20 ? cn.name.slice(0, 18) + '...' : cn.name,
        fullLabel: cn.qualifiedName || cn.name,
        elementType: cn.elementType,
        color: getElementTypeColor(cn.elementType),
        shape: getNodeShape(cn.elementType),
        isEndpoint: false,
        isHighlighted: true,
      },
    })
    if (result.elementA) {
      edges.push({
        data: {
          id: `sim_a_${cn.id}`,
          source: result.elementA.id,
          target: cn.id,
          edgeType: 'NEIGHBOR',
          color: '#8b5cf6',
          label: '',
        },
      })
    }
    if (result.elementB) {
      edges.push({
        data: {
          id: `sim_b_${cn.id}`,
          source: result.elementB.id,
          target: cn.id,
          edgeType: 'NEIGHBOR',
          color: '#8b5cf6',
          label: '',
        },
      })
    }
  }

  return [...nodes, ...edges]
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const CY_STYLESHEET: any[] = [
  {
    selector: 'node',
    style: {
      'background-color': 'data(color)',
      'label': 'data(label)',
      'color': '#e2e8f0',
      'font-size': 10,
      'text-valign': 'center',
      'text-halign': 'center',
      'text-wrap': 'wrap',
      'text-max-width': '90px',
      'shape': 'data(shape)' as never,
      'width': 70,
      'height': 35,
      'border-width': 2,
      'border-color': 'transparent',
    } as cytoscape.Css.Node,
  },
  {
    selector: 'node[?isEndpoint]',
    style: {
      'border-color': '#f59e0b',
      'border-width': 3,
      'width': 90,
      'height': 45,
    } as cytoscape.Css.Node,
  },
  {
    selector: 'node[?isHighlighted]',
    style: {
      'border-color': '#8b5cf6',
      'border-width': 2,
    } as cytoscape.Css.Node,
  },
  {
    selector: 'node:selected',
    style: {
      'border-color': '#ffffff',
      'border-width': 3,
    } as cytoscape.Css.Node,
  },
  {
    selector: 'edge',
    style: {
      'width': 1.5,
      'line-color': 'data(color)',
      'target-arrow-color': 'data(color)',
      'target-arrow-shape': 'triangle',
      'curve-style': 'bezier',
      'opacity': 0.7,
      'label': 'data(label)',
      'font-size': 8,
      'color': '#94a3b8',
      'text-background-color': '#0f172a',
      'text-background-opacity': 1,
      'text-background-padding': '2px',
    } as cytoscape.Css.Edge,
  },
]

// ---- Main Component ----

export default function ConnectivityExplorer({ onSelectElement, externalElementA, externalElementB }: Props) {
  const [elementAId, setElementAId] = useState('')
  const [elementBId, setElementBId] = useState('')
  const [elementAName, setElementAName] = useState('')
  const [elementBName, setElementBName] = useState('')

  // React to external element picks (from ElementDetail "Set A" / "Set B" buttons)
  useEffect(() => {
    if (externalElementA) {
      setElementAId(externalElementA.id)
      setElementAName(externalElementA.qualifiedName || externalElementA.name)
    }
  }, [externalElementA])

  useEffect(() => {
    if (externalElementB) {
      setElementBId(externalElementB.id)
      setElementBName(externalElementB.qualifiedName || externalElementB.name)
    }
  }, [externalElementB])
  const [algorithm, setAlgorithm] = useState<AlgorithmId>('shortest')
  const [direction, setDirection] = useState<EdgeDirection>('BOTH')
  const [selectedEdgeTypes, setSelectedEdgeTypes] = useState<EdgeType[]>([...ALL_EDGE_TYPES])
  const [maxDepth, setMaxDepth] = useState(6)
  const [maxPaths, setMaxPaths] = useState(50)

  const [pathResult, setPathResult] = useState<PathResult | null>(null)
  const [simResult, setSimResult] = useState<SimilarityResult | null>(null)
  const [isRunning, setIsRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [highlightedPath, setHighlightedPath] = useState<number | null>(null)

  const cyRef = useRef<cytoscape.Core | null>(null)

  const edgesParam = selectedEdgeTypes.length < ALL_EDGE_TYPES.length
    ? selectedEdgeTypes.join(',') : undefined

  const runQuery = useCallback(async () => {
    if (!elementAId || !elementBId) return
    setIsRunning(true)
    setError(null)
    setPathResult(null)
    setSimResult(null)
    setHighlightedPath(null)

    try {
      const params = {
        from: elementAId,
        to: elementBId,
        depth: maxDepth,
        direction,
        edges: edgesParam,
        maxPaths,
      }

      switch (algorithm) {
        case 'shortest': {
          const result = await findShortestPath(params)
          setPathResult(result)
          break
        }
        case 'all-shortest': {
          const result = await findAllShortestPaths(params)
          setPathResult(result)
          break
        }
        case 'all-paths': {
          const result = await findAllPaths(params)
          setPathResult(result)
          break
        }
        case 'similarity': {
          const result = await getSimilarity({ a: elementAId, b: elementBId, direction, edges: edgesParam })
          setSimResult(result)
          break
        }
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Query failed')
    } finally {
      setIsRunning(false)
    }
  }, [elementAId, elementBId, algorithm, direction, edgesParam, maxDepth, maxPaths])

  const toggleEdgeType = (t: EdgeType) =>
    setSelectedEdgeTypes((prev) => prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t])

  // Build cytoscape elements
  const cyElements = useMemo(() => {
    if (simResult) {
      return buildSimilarityCyElements(simResult)
    }
    if (pathResult && pathResult.paths.length > 0) {
      const paths = highlightedPath !== null ? [pathResult.paths[highlightedPath]] : pathResult.paths
      const edges = highlightedPath !== null
        ? [pathResult.pathEdges[highlightedPath]]
        : pathResult.pathEdges
      return buildCyElements(paths, edges, undefined, elementAId, elementBId)
    }
    return []
  }, [pathResult, simResult, highlightedPath, elementAId, elementBId])

  const handleCyInit = useCallback((cy: cytoscape.Core) => {
    cyRef.current = cy
    cy.on('tap', 'node', (evt) => {
      const nodeId = evt.target.id()
      getElement(nodeId).then((el) => onSelectElement(el)).catch(() => {})
    })
  }, [onSelectElement])

  useEffect(() => {
    if (cyRef.current && cyElements.length > 0) {
      setTimeout(() => {
        cyRef.current?.layout({
          name: 'dagre', rankDir: 'LR', nodeSep: 40, rankSep: 60, padding: 20,
        } as never).run()
      }, 50)
    }
  }, [cyElements])

  const fitGraph = useCallback(() => cyRef.current?.fit(undefined, 30), [])

  const algInfo = ALGORITHMS.find((a) => a.id === algorithm)

  return (
    <div className="flex flex-col h-full">
      {/* Controls */}
      <div className="border-b border-slate-700 px-3 py-3 space-y-3">
        {/* Element pickers */}
        <div className="flex items-end gap-2">
          <div className="flex-1">
            <ElementPicker
              label="Element A"
              value={elementAId}
              onChange={(id, el) => { setElementAId(id); if (el) setElementAName(el.qualifiedName || el.name); else if (!id) setElementAName('') }}
              placeholder="Search element A..."
              externalName={elementAName}
            />
          </div>
          <button
            onClick={() => {
              const tmpId = elementAId; const tmpName = elementAName
              setElementAId(elementBId); setElementAName(elementBName)
              setElementBId(tmpId); setElementBName(tmpName)
            }}
            className="p-1.5 mb-0.5 text-slate-500 hover:text-white transition-colors rounded hover:bg-slate-700"
            title="Swap A ↔ B"
          >
            ⇄
          </button>
          <div className="flex-1">
            <ElementPicker
              label="Element B"
              value={elementBId}
              onChange={(id, el) => { setElementBId(id); if (el) setElementBName(el.qualifiedName || el.name); else if (!id) setElementBName('') }}
              placeholder="Search element B..."
              externalName={elementBName}
            />
          </div>
        </div>

        {/* Algorithm selector */}
        <div>
          <div className="text-[10px] text-slate-500 uppercase tracking-wider font-medium mb-1">Algorithm</div>
          <div className="flex gap-1">
            {ALGORITHMS.map((alg) => (
              <button
                key={alg.id}
                onClick={() => setAlgorithm(alg.id)}
                className={`flex items-center gap-1 px-2.5 py-1.5 rounded text-xs transition-colors ${
                  algorithm === alg.id
                    ? 'bg-blue-600/30 text-blue-400 border border-blue-600/50'
                    : 'bg-slate-800 text-slate-400 border border-slate-700 hover:bg-slate-700'
                }`}
              >
                {alg.icon}
                {alg.label}
              </button>
            ))}
          </div>
          {algInfo && (
            <div className="flex items-center gap-1 mt-1 text-[10px] text-slate-600">
              <Info size={10} />
              {algInfo.desc}
            </div>
          )}
        </div>

        {/* Options row */}
        <div className="flex items-end gap-3">
          {/* Direction */}
          <div>
            <div className="text-[10px] text-slate-500 uppercase tracking-wider font-medium mb-1">Direction</div>
            <div className="flex gap-0.5 bg-slate-800 rounded p-0.5">
              {(['BOTH', 'OUTGOING', 'INCOMING'] as EdgeDirection[]).map((d) => (
                <button
                  key={d}
                  onClick={() => setDirection(d)}
                  className={`px-2 py-1 rounded text-[10px] transition-colors ${
                    direction === d ? 'bg-slate-600 text-white' : 'text-slate-500 hover:text-white'
                  }`}
                >
                  {d}
                </button>
              ))}
            </div>
          </div>

          {/* Max Depth */}
          {algorithm !== 'similarity' && (
            <div>
              <div className="text-[10px] text-slate-500 uppercase tracking-wider font-medium mb-1">
                Depth: {maxDepth}
              </div>
              <input
                type="range" min={1} max={10} value={maxDepth}
                onChange={(e) => setMaxDepth(Number(e.target.value))}
                className="w-24 accent-blue-500"
              />
            </div>
          )}

          {/* Max Paths */}
          {(algorithm === 'all-shortest' || algorithm === 'all-paths') && (
            <div>
              <div className="text-[10px] text-slate-500 uppercase tracking-wider font-medium mb-1">
                Max Paths: {maxPaths}
              </div>
              <input
                type="range" min={5} max={200} step={5} value={maxPaths}
                onChange={(e) => setMaxPaths(Number(e.target.value))}
                className="w-24 accent-blue-500"
              />
            </div>
          )}

          {/* Run button */}
          <button
            onClick={runQuery}
            disabled={!elementAId || !elementBId || isRunning}
            className="flex items-center gap-1.5 px-4 py-1.5 bg-blue-600 hover:bg-blue-500 disabled:opacity-40 disabled:cursor-not-allowed text-white rounded text-xs font-medium transition-colors ml-auto"
          >
            {isRunning ? (
              <div className="w-3 h-3 border border-white border-t-transparent rounded-full animate-spin" />
            ) : (
              <Play size={12} />
            )}
            Run
          </button>
        </div>

        {/* Edge types */}
        <div>
          <div className="text-[10px] text-slate-500 uppercase tracking-wider font-medium mb-1">Edge Types</div>
          <div className="flex flex-wrap gap-1">
            {ALL_EDGE_TYPES.map((t) => (
              <button
                key={t}
                onClick={() => toggleEdgeType(t)}
                className={`px-1.5 py-0.5 rounded text-[9px] border transition-colors ${
                  selectedEdgeTypes.includes(t) ? 'border-opacity-100 text-white' : 'bg-slate-800 text-slate-600 border-slate-700'
                }`}
                style={selectedEdgeTypes.includes(t) ? {
                  backgroundColor: (EDGE_COLORS[t] || '#64748b') + '30',
                  borderColor: EDGE_COLORS[t] || '#64748b',
                  color: EDGE_COLORS[t] || '#94a3b8',
                } : {}}
              >
                {t}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Results area */}
      <div className="flex-1 flex overflow-hidden">
        {/* Graph canvas */}
        <div className="flex-1 relative bg-slate-950">
          {!pathResult && !simResult && !isRunning && !error && (
            <div className="absolute inset-0 flex flex-col items-center justify-center text-slate-600">
              <GitBranch size={48} className="mb-3 opacity-30" />
              <p className="text-sm">Connectivity Explorer</p>
              <p className="text-xs mt-1">Pick two elements and run an algorithm to see how they connect</p>
            </div>
          )}

          {isRunning && (
            <div className="absolute inset-0 flex items-center justify-center">
              <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
            </div>
          )}

          {error && (
            <div className="absolute inset-0 flex items-center justify-center text-red-400 text-sm">
              {error}
            </div>
          )}

          {!isRunning && cyElements.length > 0 && (
            <>
              <CytoscapeComponent
                elements={cyElements}
                stylesheet={CY_STYLESHEET}
                layout={{ name: 'dagre', rankDir: 'LR', nodeSep: 40, rankSep: 60, padding: 20 } as never}
                cy={handleCyInit}
                style={{ width: '100%', height: '100%' }}
              />
              <div className="absolute top-2 right-2 flex gap-1">
                <button onClick={fitGraph} className="p-1.5 bg-slate-800/80 hover:bg-slate-700 rounded text-slate-400 hover:text-white" title="Fit">
                  <Maximize2 size={14} />
                </button>
              </div>
            </>
          )}

          {!isRunning && !error && (pathResult || simResult) && cyElements.length === 0 && (
            <div className="absolute inset-0 flex flex-col items-center justify-center text-slate-600 text-sm">
              <p>No connections found</p>
              <p className="text-xs mt-1">Try increasing depth or changing edge types</p>
            </div>
          )}
        </div>

        {/* Results sidebar */}
        {(pathResult || simResult) && (
          <div className="w-72 shrink-0 border-l border-slate-700 bg-slate-900 overflow-y-auto">
            {/* Similarity results */}
            {simResult && (
              <div className="p-3 space-y-3">
                <div className="text-xs font-medium text-slate-300">Similarity Metrics</div>

                <div className="grid grid-cols-2 gap-2">
                  <MetricCard
                    label="Jaccard"
                    value={`${(simResult.jaccardSimilarity * 100).toFixed(1)}%`}
                    detail={`${simResult.jaccardSimilarity.toFixed(4)}`}
                  />
                  <MetricCard
                    label="Adamic-Adar"
                    value={simResult.adamicAdarIndex.toFixed(2)}
                    detail="weighted score"
                  />
                  <MetricCard
                    label="Common"
                    value={`${simResult.commonNeighbors.length}`}
                    detail="shared neighbors"
                  />
                  <MetricCard
                    label="Time"
                    value={`${simResult.computeTimeMs}ms`}
                    detail="compute time"
                  />
                </div>

                <div className="space-y-1">
                  <div className="text-[10px] text-slate-500 uppercase tracking-wider">
                    Neighbors: A={simResult.neighborCountA} B={simResult.neighborCountB}
                  </div>
                </div>

                {simResult.commonNeighbors.length > 0 && (
                  <div>
                    <div className="text-[10px] text-slate-500 uppercase tracking-wider mb-1">
                      Common Neighbors ({simResult.commonNeighbors.length})
                    </div>
                    <div className="space-y-0.5 max-h-96 overflow-y-auto">
                      {simResult.commonNeighbors.map((cn) => (
                        <button
                          key={cn.id}
                          onClick={() => onSelectElement(cn)}
                          className="w-full text-left px-2 py-1 hover:bg-slate-800 rounded transition-colors"
                        >
                          <div className="flex items-center gap-1">
                            <span className={`px-1 py-0.5 rounded text-[8px] border ${getElementTypeBgClass(cn.elementType)}`}>
                              {getElementTypeLabel(cn.elementType)}
                            </span>
                            <span className="text-[10px] text-slate-300 truncate">{cn.name}</span>
                          </div>
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}

            {/* Path results */}
            {pathResult && (
              <div className="p-3 space-y-3">
                <div className="flex items-center justify-between">
                  <div className="text-xs font-medium text-slate-300">
                    {pathResult.paths.length} Path{pathResult.paths.length !== 1 ? 's' : ''} Found
                  </div>
                  <div className="text-[10px] text-slate-600">{pathResult.computeTimeMs}ms</div>
                </div>

                {pathResult.shortestPathLength >= 0 && (
                  <div className="text-[10px] text-slate-500">
                    Shortest: {pathResult.shortestPathLength} hop{pathResult.shortestPathLength !== 1 ? 's' : ''}
                  </div>
                )}

                <div className="space-y-2 max-h-[600px] overflow-y-auto">
                  {pathResult.paths.map((path, pi) => (
                    <button
                      key={pi}
                      onClick={() => setHighlightedPath(highlightedPath === pi ? null : pi)}
                      className={`w-full text-left rounded border transition-colors p-2 ${
                        highlightedPath === pi
                          ? 'bg-blue-600/20 border-blue-600/40'
                          : 'bg-slate-800/50 border-slate-700 hover:border-slate-600'
                      }`}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-[10px] text-slate-400 font-medium">
                          Path {pi + 1}
                        </span>
                        <span className="text-[10px] text-slate-600">{path.length - 1} hops</span>
                      </div>
                      <div className="space-y-0.5">
                        {path.map((el, ei) => (
                          <div key={el.id} className="flex items-center gap-1">
                            {ei > 0 && (
                              <span className="text-[9px] text-slate-600 ml-1">
                                {pathResult.pathEdges[pi] && pathResult.pathEdges[pi][ei - 1]
                                  ? `[${pathResult.pathEdges[pi][ei - 1].edgeType}]`
                                  : '->'}
                              </span>
                            )}
                            <span className={`px-0.5 py-0 rounded text-[8px] border ${getElementTypeBgClass(el.elementType)}`}>
                              {el.elementType.slice(0, 3)}
                            </span>
                            <span className="text-[10px] text-slate-300 truncate">{el.name}</span>
                          </div>
                        ))}
                      </div>
                    </button>
                  ))}
                </div>

                {highlightedPath !== null && (
                  <button
                    onClick={() => setHighlightedPath(null)}
                    className="w-full text-center text-[10px] text-slate-500 hover:text-slate-300 py-1 transition-colors"
                  >
                    Show all paths
                  </button>
                )}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function MetricCard({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <div className="bg-slate-800 rounded p-2 border border-slate-700">
      <div className="text-[10px] text-slate-500 uppercase tracking-wider">{label}</div>
      <div className="text-lg font-semibold text-slate-200 mt-0.5">{value}</div>
      <div className="text-[10px] text-slate-600">{detail}</div>
    </div>
  )
}
