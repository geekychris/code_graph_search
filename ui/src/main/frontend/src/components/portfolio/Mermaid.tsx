import React, { useEffect, useRef, useState } from 'react'
import { Maximize2, X } from 'lucide-react'

let counter = 0
let loaded: Promise<typeof import('mermaid').default> | null = null

function mermaid() {
  if (!loaded) {
    loaded = import('mermaid').then((m) => {
      m.default.initialize({
        startOnLoad: false,
        theme: 'dark',
        securityLevel: 'strict',
        fontFamily: 'system-ui, sans-serif',
        themeVariables: { background: '#0f172a', primaryColor: '#1e293b', primaryBorderColor: '#3b82f6',
          primaryTextColor: '#e2e8f0', lineColor: '#64748b', fontSize: '14px' },
        flowchart: { curve: 'basis', htmlLabels: true },
      })
      return m.default
    })
  }
  return loaded
}

/** Renders Mermaid source; shows the source if it doesn't parse. */
export default function Mermaid({ source, className = '' }: { source: string; className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [error, setError] = useState<string | null>(null)
  const [svg, setSvg] = useState<string | null>(null)
  const [full, setFull] = useState(false)

  useEffect(() => {
    let cancelled = false
    setError(null)
    mermaid().then(async (m) => {
      try {
        const { svg } = await m.render(`mmd-${++counter}`, source)
        if (!cancelled && ref.current) {
          ref.current.innerHTML = svg
          fit(ref.current)
          setSvg(svg)
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e))
      }
    })
    return () => { cancelled = true }
  }, [source])

  if (error) {
    return (
      <div className={className}>
        <div className="text-xs text-amber-400 mb-2">The drawing didn't render: {error.split('\n')[0]}</div>
        <pre className="text-xs text-slate-400 bg-slate-950 rounded p-3 overflow-auto">{source}</pre>
      </div>
    )
  }
  return (
    <div className={`relative ${className}`}>
      <div ref={ref} className="mermaid-host overflow-x-auto flex justify-center" />
      {svg && (
        <button onClick={() => setFull(true)} title="Full screen"
          className="absolute top-0 right-0 p-1.5 rounded bg-slate-800/90 border border-slate-700 text-slate-300 hover:text-white">
          <Maximize2 size={13} />
        </button>
      )}
      {full && svg && (
        <div className="fixed inset-0 z-50 bg-slate-950/95 overflow-auto p-8" onClick={() => setFull(false)}>
          <button className="fixed top-4 right-4 p-2 rounded bg-slate-800 text-slate-200 hover:text-white"><X size={16} /></button>
          <div className="min-w-max [&_svg]:!max-w-none" onClick={(e) => e.stopPropagation()}
            dangerouslySetInnerHTML={{ __html: svg }} />
        </div>
      )}
    </div>
  )
}

/**
 * Wide drawings shrink to unreadable text when fitted to the column: keep at least
 * 70% of their natural size and let the column scroll sideways instead.
 */
function fit(host: HTMLDivElement) {
  const el = host.querySelector('svg')
  if (!el) return
  const natural = el.viewBox?.baseVal?.width || 0
  const available = host.clientWidth
  el.style.maxWidth = 'none'
  el.style.height = 'auto'
  el.style.width = natural > 0 ? `${Math.min(natural, Math.max(available, natural * 0.7))}px` : '100%'
  el.style.flexShrink = '0'
  if (natural * 0.7 > available) host.classList.remove('justify-center')
}
