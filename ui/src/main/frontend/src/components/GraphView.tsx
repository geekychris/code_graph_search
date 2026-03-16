import React, { useState, useCallback, useRef, useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import CytoscapeComponent from 'react-cytoscapejs'
import cytoscape from 'cytoscape'
// @ts-ignore
import dagre from 'cytoscape-dagre'
// @ts-ignore
import coseBilkent from 'cytoscape-cose-bilkent'
import { Network, ZoomIn, ZoomOut, Maximize2, Target, Filter, ChevronDown } from 'lucide-react'
import { getSubGraph, getElement, getCallers, getCallees, getChildren, getUsages, getParent, getSuperclass, getSubclasses, getInterfaces, getImplementors, getMethods, getFields } from '../api/client'
import type { Element, EdgeType } from '../types'
import { getElementTypeColor, getElementTypeLabel } from '../utils/elementColors'

try { cytoscape.use(dagre) } catch (_) {}
try { cytoscape.use(coseBilkent) } catch (_) {}

interface Props {
  rootElementId?: string
  onSelectElement: (element: Element) => void
}

const EDGE_TYPES: EdgeType[] = ['CONTAINS', 'CALLS', 'EXTENDS', 'IMPLEMENTS', 'OVERRIDES', 'DOCUMENTS', 'IMPORTS']

const EDGE_COLORS: Record<string, string> = {
  CALLS: '#f97316',
  EXTENDS: '#3b82f6',
  IMPLEMENTS: '#06b6d4',
  CONTAINS: '#64748b',
  OVERRIDES: '#a855f7',
  DOCUMENTS: '#94a3b8',
  IMPORTS: '#22c55e',
  ANNOTATES: '#eab308',
  DEPENDS_ON: '#ef4444',
}

const LAYOUTS = [
  { value: 'dagre', label: 'Hierarchical (Dagre)' },
  { value: 'cose-bilkent', label: 'Force (Cose-Bilkent)' },
  { value: 'breadthfirst', label: 'Breadth First' },
  { value: 'circle', label: 'Circle' },
]

function getNodeShape(type: string): string {
  if (['METHOD', 'FUNCTION', 'CONSTRUCTOR'].includes(type)) return 'rectangle'
  if (['FIELD', 'PROPERTY', 'ENUM_CONSTANT'].includes(type)) return 'diamond'
  return 'ellipse'
}

export default function GraphView({ rootElementId, onSelectElement }: Props) {
  const [localRootId, setLocalRootId] = useState<string>(rootElementId || '')
  const [depth, setDepth] = useState(2)
  const [selectedEdgeTypes, setSelectedEdgeTypes] = useState<EdgeType[]>([...EDGE_TYPES])
  const [layout, setLayout] = useState('dagre')
  const [showControls, setShowControls] = useState(true)
  const cyRef = useRef<cytoscape.Core | null>(null)
  const [extraNodes, setExtraNodes] = useState<Map<string, { nodes: Element[]; edges: { id: string; fromId: string; toId: string; edgeType: string }[] }>>(new Map())
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; nodeId: string } | null>(null)
  const queryClient = useQueryClient()

  // Sync external rootElementId
  useEffect(() => {
    if (rootElementId && rootElementId !== localRootId) {
      setLocalRootId(rootElementId)
    }
  }, [rootElementId])

  const { data: graphData, isLoading, isError } = useQuery({
    queryKey: ['graph', localRootId, depth, selectedEdgeTypes],
    queryFn: () => getSubGraph(localRootId, depth, selectedEdgeTypes.length < EDGE_TYPES.length ? selectedEdgeTypes : undefined),
    enabled: !!localRootId,
  })

  const toggleEdgeType = (t: EdgeType) =>
    setSelectedEdgeTypes((prev) => prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t])

  // Build cytoscape elements from graph data + extra expansions
  const cyElements = React.useMemo(() => {
    if (!graphData) return []
    const nodeMap = new Map<string, Element>()
    graphData.nodes.forEach((n) => nodeMap.set(n.id, n))
    extraNodes.forEach((v) => v.nodes.forEach((n) => nodeMap.set(n.id, n)))

    const edgeMap = new Map<string, { id: string; fromId: string; toId: string; edgeType: string }>()
    graphData.edges.forEach((e) => edgeMap.set(e.id, e))
    extraNodes.forEach((v) => v.edges.forEach((e) => edgeMap.set(e.id, e)))

    const nodes: cytoscape.ElementDefinition[] = Array.from(nodeMap.values()).map((n) => ({
      data: {
        id: n.id,
        label: n.name.length > 30 ? n.name.slice(0, 28) + '…' : n.name,
        fullLabel: n.name,
        elementType: n.elementType,
        color: getElementTypeColor(n.elementType),
        shape: getNodeShape(n.elementType),
        isRoot: n.id === localRootId,
      },
    }))

    const edges: cytoscape.ElementDefinition[] = Array.from(edgeMap.values())
      .filter((e) => nodeMap.has(e.fromId) && nodeMap.has(e.toId))
      .map((e) => ({
        data: {
          id: e.id,
          source: e.fromId,
          target: e.toId,
          edgeType: e.edgeType,
          color: EDGE_COLORS[e.edgeType] || '#64748b',
          label: e.edgeType,
        },
      }))

    return [...nodes, ...edges]
  }, [graphData, extraNodes, localRootId])

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const stylesheet: any[] = [
    {
      selector: 'node',
      style: {
        'background-color': 'data(color)',
        'label': 'data(label)',
        'color': '#e2e8f0',
        'font-size': 11,
        'text-valign': 'center',
        'text-halign': 'center',
        'text-wrap': 'wrap',
        'text-max-width': '100px',
        'shape': 'data(shape)' as never,
        'width': 80,
        'height': 40,
        'border-width': 2,
        'border-color': 'transparent',
        'overlay-padding': 4,
      } as cytoscape.Css.Node,
    },
    {
      selector: 'node[?isRoot]',
      style: {
        'border-color': '#ffffff',
        'border-width': 3,
        'width': 100,
        'height': 50,
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
        'label': '',
        'font-size': 9,
        'color': '#94a3b8',
        'text-background-color': '#1e293b',
        'text-background-opacity': 1,
        'text-background-padding': '2px',
      } as cytoscape.Css.Edge,
    },
    {
      selector: 'edge:selected, edge:active',
      style: {
        'label': 'data(label)',
        'opacity': 1,
        'width': 2.5,
      } as cytoscape.Css.Edge,
    },
  ]

  const getLayoutConfig = useCallback((name: string) => {
    if (name === 'dagre') return { name: 'dagre', rankDir: 'TB', nodeSep: 50, rankSep: 80, padding: 20 }
    if (name === 'cose-bilkent') return { name: 'cose-bilkent', animate: false, padding: 20, nodeDimensionsIncludeLabels: true }
    if (name === 'breadthfirst') return { name: 'breadthfirst', directed: true, padding: 20, spacingFactor: 1.5 }
    return { name, padding: 20 }
  }, [])

  // Add elements from a relationship query to the graph
  const expandRelationship = useCallback(async (nodeId: string, label: string, fetchFn: (id: string) => Promise<Element[] | Element | null>, edgeType: string, direction: 'out' | 'in') => {
    try {
      const result = await fetchFn(nodeId)
      const elements: Element[] = result == null ? [] : Array.isArray(result) ? result : [result]
      if (elements.length === 0) return

      const syntheticEdges = elements.map((el) => ({
        id: `${edgeType}:${direction === 'out' ? nodeId : el.id}:${direction === 'out' ? el.id : nodeId}`,
        fromId: direction === 'out' ? nodeId : el.id,
        toId: direction === 'out' ? el.id : nodeId,
        edgeType,
      }))

      setExtraNodes((prev) => {
        const next = new Map(prev)
        const key = `${nodeId}:${label}`
        next.set(key, { nodes: elements, edges: syntheticEdges })
        return next
      })
    } catch { /* ignore */ }
  }, [])

  const handleContextMenuAction = useCallback((action: string, nodeId: string) => {
    setContextMenu(null)
    switch (action) {
      case 'callers':    expandRelationship(nodeId, 'callers', getCallers, 'CALLS', 'in'); break
      case 'callees':    expandRelationship(nodeId, 'callees', getCallees, 'CALLS', 'out'); break
      case 'children':   expandRelationship(nodeId, 'children', getChildren, 'CONTAINS', 'out'); break
      case 'parent':     expandRelationship(nodeId, 'parent', getParent, 'CONTAINS', 'in'); break
      case 'usages':     expandRelationship(nodeId, 'usages', getUsages, 'REFERENCES', 'in'); break
      case 'methods':    expandRelationship(nodeId, 'methods', getMethods, 'CONTAINS', 'out'); break
      case 'fields':     expandRelationship(nodeId, 'fields', getFields, 'CONTAINS', 'out'); break
      case 'superclass': expandRelationship(nodeId, 'superclass', getSuperclass, 'EXTENDS', 'out'); break
      case 'subclasses': expandRelationship(nodeId, 'subclasses', getSubclasses, 'EXTENDS', 'in'); break
      case 'interfaces': expandRelationship(nodeId, 'interfaces', getInterfaces, 'IMPLEMENTS', 'out'); break
      case 'implementors': expandRelationship(nodeId, 'implementors', getImplementors, 'IMPLEMENTS', 'in'); break
      case 'expand':     getSubGraph(nodeId, 1).then((data) => {
        setExtraNodes((prev) => { const next = new Map(prev); next.set(nodeId, { nodes: data.nodes, edges: data.edges }); return next })
      }).catch(() => {}); break
      case 'root':       setLocalRootId(nodeId); setExtraNodes(new Map()); break
    }
  }, [expandRelationship])

  const handleCyInit = useCallback((cy: cytoscape.Core) => {
    cyRef.current = cy

    cy.on('tap', 'node', (evt) => {
      const nodeId = evt.target.id()
      setContextMenu(null)
      getElement(nodeId).then((el) => onSelectElement(el)).catch(() => {})
    })

    cy.on('dbltap', 'node', (evt) => {
      const nodeId = evt.target.id()
      getSubGraph(nodeId, 1).then((data) => {
        setExtraNodes((prev) => {
          const next = new Map(prev)
          next.set(nodeId, { nodes: data.nodes, edges: data.edges })
          return next
        })
      }).catch(() => {})
    })

    cy.on('cxttap', 'node', (evt) => {
      const nodeId = evt.target.id()
      const pos = evt.renderedPosition
      const container = cy.container()
      if (!container) return
      const rect = container.getBoundingClientRect()
      setContextMenu({ x: rect.left + pos.x, y: rect.top + pos.y, nodeId })
    })

    // Close context menu on background tap
    cy.on('tap', (evt) => {
      if (evt.target === cy) setContextMenu(null)
    })
  }, [onSelectElement])

  const runLayout = useCallback(() => {
    cyRef.current?.layout(getLayoutConfig(layout)).run()
  }, [layout, getLayoutConfig])

  const fitGraph = useCallback(() => {
    cyRef.current?.fit(undefined, 30)
  }, [])

  const centerGraph = useCallback(() => {
    cyRef.current?.center()
  }, [])

  const expandSelected = useCallback(() => {
    const selected = cyRef.current?.$(':selected')
    if (!selected || selected.length === 0) return
    const nodeId = selected[0].id()
    getSubGraph(nodeId, 1).then((data) => {
      setExtraNodes((prev) => {
        const next = new Map(prev)
        next.set(nodeId, { nodes: data.nodes, edges: data.edges })
        return next
      })
    }).catch(() => {})
  }, [])

  useEffect(() => {
    if (cyRef.current && cyElements.length > 0) {
      runLayout()
    }
  }, [cyElements, layout])

  return (
    <div className="flex flex-col h-full">
      {/* Controls */}
      <div className="border-b border-slate-700">
        <div className="flex items-center gap-2 px-3 py-2">
          <button
            onClick={() => setShowControls((v) => !v)}
            className="flex items-center gap-1.5 text-xs text-slate-400 hover:text-white transition-colors"
          >
            <Filter size={13} />
            Controls
            <ChevronDown size={13} className={`transition-transform ${showControls ? '' : '-rotate-90'}`} />
          </button>
          <div className="flex gap-1 ml-auto">
            <button onClick={fitGraph} className="p-1.5 bg-slate-700 hover:bg-slate-600 rounded text-slate-400 hover:text-white" title="Fit">
              <Maximize2 size={14} />
            </button>
            <button onClick={centerGraph} className="p-1.5 bg-slate-700 hover:bg-slate-600 rounded text-slate-400 hover:text-white" title="Center">
              <Target size={14} />
            </button>
            <button onClick={expandSelected} className="p-1.5 bg-slate-700 hover:bg-slate-600 rounded text-slate-400 hover:text-white" title="Expand selected node">
              <Network size={14} />
            </button>
          </div>
        </div>

        {showControls && (
          <div className="px-3 pb-3 space-y-2">
            {/* Root element */}
            <div className="flex items-center gap-2">
              <label className="text-xs text-slate-500 w-16 shrink-0">Root ID</label>
              <input
                value={localRootId}
                onChange={(e) => setLocalRootId(e.target.value)}
                placeholder="Enter element ID..."
                className="flex-1 px-2 py-1 bg-slate-700 border border-slate-600 rounded text-xs text-slate-300 font-mono focus:outline-none focus:border-blue-500"
              />
            </div>

            {/* Depth */}
            <div className="flex items-center gap-2">
              <label className="text-xs text-slate-500 w-16 shrink-0">Depth {depth}</label>
              <input
                type="range" min={1} max={5} value={depth}
                onChange={(e) => setDepth(Number(e.target.value))}
                className="flex-1 accent-blue-500"
              />
            </div>

            {/* Layout */}
            <div className="flex items-center gap-2">
              <label className="text-xs text-slate-500 w-16 shrink-0">Layout</label>
              <select
                value={layout}
                onChange={(e) => setLayout(e.target.value)}
                className="flex-1 px-2 py-1 bg-slate-700 border border-slate-600 rounded text-xs text-slate-300 focus:outline-none focus:border-blue-500"
              >
                {LAYOUTS.map((l) => <option key={l.value} value={l.value}>{l.label}</option>)}
              </select>
              <button
                onClick={runLayout}
                className="px-2 py-1 bg-blue-600/30 hover:bg-blue-600/50 text-blue-400 rounded text-xs border border-blue-600/30 transition-colors"
              >
                Apply
              </button>
            </div>

            {/* Edge types */}
            <div>
              <div className="text-xs text-slate-500 mb-1.5">Edge Types</div>
              <div className="flex flex-wrap gap-1">
                {EDGE_TYPES.map((t) => (
                  <button
                    key={t}
                    onClick={() => toggleEdgeType(t)}
                    className={`px-2 py-0.5 rounded text-[10px] border transition-colors ${
                      selectedEdgeTypes.includes(t)
                        ? 'border-opacity-100 text-white'
                        : 'bg-slate-800 text-slate-600 border-slate-700'
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
        )}
      </div>

      {/* Graph canvas */}
      <div className="flex-1 relative bg-slate-950" onContextMenu={(e) => e.preventDefault()}>
        {!localRootId && (
          <div className="absolute inset-0 flex flex-col items-center justify-center text-slate-600">
            <Network size={48} className="mb-3 opacity-30" />
            <p className="text-sm">No element selected</p>
            <p className="text-xs mt-1">Select an element from Search or enter an ID above</p>
          </div>
        )}

        {localRootId && isLoading && (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
          </div>
        )}

        {localRootId && isError && (
          <div className="absolute inset-0 flex items-center justify-center text-red-400 text-sm">
            Failed to load graph data
          </div>
        )}

        {!isLoading && cyElements.length > 0 && (
          <CytoscapeComponent
            elements={cyElements}
            stylesheet={stylesheet}
            layout={getLayoutConfig(layout)}
            cy={handleCyInit}
            style={{ width: '100%', height: '100%' }}
          />
        )}

        {!isLoading && !isError && localRootId && cyElements.length === 0 && (
          <div className="absolute inset-0 flex items-center justify-center text-slate-600 text-sm">
            No graph data found for this element
          </div>
        )}
      </div>

      {/* Legend */}
      <div className="border-t border-slate-800 px-3 py-2 flex flex-wrap gap-3">
        {EDGE_TYPES.filter((t) => selectedEdgeTypes.includes(t)).map((t) => (
          <div key={t} className="flex items-center gap-1">
            <div
              className="w-4 h-0.5 rounded"
              style={{ backgroundColor: EDGE_COLORS[t] || '#64748b' }}
            />
            <span className="text-[10px] text-slate-600">{t}</span>
          </div>
        ))}
      </div>

      {/* Right-click context menu */}
      {contextMenu && (
        <div
          className="fixed z-50 bg-slate-800 border border-slate-600 rounded-lg shadow-xl py-1 min-w-[180px] text-xs"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onMouseLeave={() => setContextMenu(null)}
        >
          <div className="px-3 py-1.5 text-slate-500 font-medium border-b border-slate-700 text-[10px] uppercase tracking-wider">
            Expand Relationships
          </div>
          {[
            { action: 'expand', label: 'Expand All (depth 1)', section: 'general' },
            { action: 'root', label: 'Set as Root', section: 'general' },
            { action: 'parent', label: 'Parent', section: 'structure' },
            { action: 'children', label: 'Children', section: 'structure' },
            { action: 'methods', label: 'Methods', section: 'structure' },
            { action: 'fields', label: 'Fields', section: 'structure' },
            { action: 'callers', label: 'Callers', section: 'calls' },
            { action: 'callees', label: 'Callees', section: 'calls' },
            { action: 'usages', label: 'References / Usages', section: 'refs' },
            { action: 'superclass', label: 'Superclass', section: 'hierarchy' },
            { action: 'subclasses', label: 'Subclasses', section: 'hierarchy' },
            { action: 'interfaces', label: 'Interfaces', section: 'hierarchy' },
            { action: 'implementors', label: 'Implementors', section: 'hierarchy' },
          ].map((item, i, arr) => {
            const prevSection = i > 0 ? arr[i - 1].section : null
            const showDivider = prevSection && prevSection !== item.section
            return (
              <React.Fragment key={item.action}>
                {showDivider && <div className="border-t border-slate-700 my-0.5" />}
                <button
                  onClick={() => handleContextMenuAction(item.action, contextMenu.nodeId)}
                  className="w-full text-left px-3 py-1.5 text-slate-300 hover:bg-slate-700 hover:text-white transition-colors"
                >
                  {item.label}
                </button>
              </React.Fragment>
            )
          })}
        </div>
      )}
    </div>
  )
}
