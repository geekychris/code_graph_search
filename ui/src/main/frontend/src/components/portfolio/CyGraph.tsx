import React, { useEffect, useRef } from 'react'
import cytoscape from 'cytoscape'
// @ts-ignore
import dagre from 'cytoscape-dagre'
// @ts-ignore
import coseBilkent from 'cytoscape-cose-bilkent'
import { ZoomIn, ZoomOut, Maximize2 } from 'lucide-react'

try { cytoscape.use(dagre) } catch (_) {}
try { cytoscape.use(coseBilkent) } catch (_) {}

interface Props {
  elements: cytoscape.ElementDefinition[]
  stylesheet: cytoscape.StylesheetJson
  layout: cytoscape.LayoutOptions
  onTapNode?: (id: string, data: any) => void
  onDoubleTapNode?: (id: string, data: any) => void
  onHover?: (data: any | null) => void
  className?: string
}

/**
 * A cytoscape canvas that re-lays out when its elements change. Hovering a node
 * dims everything not connected to it.
 */
export default function CyGraph({ elements, stylesheet, layout, onTapNode, onDoubleTapNode, onHover, className = '' }: Props) {
  const host = useRef<HTMLDivElement>(null)
  const cyRef = useRef<cytoscape.Core | null>(null)
  const handlers = useRef({ onTapNode, onDoubleTapNode, onHover })
  handlers.current = { onTapNode, onDoubleTapNode, onHover }

  useEffect(() => {
    if (!host.current) return
    const cy = cytoscape({
      container: host.current,
      elements,
      style: stylesheet,
      layout,
      minZoom: 0.05,
      maxZoom: 4,
      wheelSensitivity: 0.3,
    })
    // small graphs: don't blow a handful of nodes up to fill the canvas
    if (cy.zoom() > 1.2) { cy.zoom(1.2); cy.center() }
    cy.on('tap', 'node', (e) => handlers.current.onTapNode?.(e.target.id(), e.target.data()))
    cy.on('dbltap', 'node', (e) => handlers.current.onDoubleTapNode?.(e.target.id(), e.target.data()))
    cy.on('mouseover', 'node', (e) => {
      const n = e.target
      if (n.isParent()) return
      const keep = n.closedNeighborhood().union(n.ancestors())
      cy.elements().not(keep).addClass('faded')
      keep.addClass('lit')
      handlers.current.onHover?.(n.data())
    })
    cy.on('mouseout', 'node', () => {
      cy.elements().removeClass('faded lit')
      handlers.current.onHover?.(null)
    })
    cyRef.current = cy
    return () => { cy.destroy(); cyRef.current = null }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [elements, layout])

  useEffect(() => {
    cyRef.current?.style(stylesheet)
  }, [stylesheet])

  const zoom = (f: number) => {
    const cy = cyRef.current
    if (!cy) return
    cy.zoom({ level: cy.zoom() * f, renderedPosition: { x: cy.width() / 2, y: cy.height() / 2 } })
  }

  return (
    <div className={`${/\babsolute\b/.test(className) ? '' : 'relative'} ${className}`}>
      <div ref={host} className="absolute inset-0" />
      <div className="absolute bottom-3 right-3 flex flex-col gap-1">
        {[
          { icon: <ZoomIn size={14} />, f: () => zoom(1.25), t: 'Zoom in' },
          { icon: <ZoomOut size={14} />, f: () => zoom(0.8), t: 'Zoom out' },
          { icon: <Maximize2 size={14} />, f: () => cyRef.current?.fit(undefined, 30), t: 'Fit' },
        ].map((b) => (
          <button key={b.t} title={b.t} onClick={b.f}
            className="p-1.5 rounded bg-slate-800/90 border border-slate-700 text-slate-300 hover:text-white">
            {b.icon}
          </button>
        ))}
      </div>
    </div>
  )
}

/** Shared base styles: hover fading and labels that stay readable on a dark canvas. */
export const BASE_STYLE: cytoscape.StylesheetJson = [
  { selector: 'node', style: {
    label: 'data(label)', color: '#e2e8f0', 'font-size': 11, 'text-valign': 'bottom', 'text-margin-y': 4,
    'text-outline-color': '#0f172a', 'text-outline-width': 2, 'background-color': 'data(color)',
    'border-width': 0, 'transition-property': 'opacity', 'transition-duration': 150,
  } },
  { selector: 'edge', style: {
    width: 'data(width)', 'line-color': '#475569', 'target-arrow-color': '#475569', 'target-arrow-shape': 'triangle',
    'curve-style': 'bezier', 'arrow-scale': 0.8, opacity: 0.7,
  } },
  { selector: '.faded', style: { opacity: 0.12 } },
  { selector: 'edge.lit', style: { 'line-color': '#93c5fd', 'target-arrow-color': '#93c5fd', opacity: 1 } },
  { selector: 'node:selected', style: { 'border-width': 3, 'border-color': '#f8fafc' } },
]
