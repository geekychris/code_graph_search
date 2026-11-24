import React, { useEffect, useRef, useState } from 'react'

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

  useEffect(() => {
    let cancelled = false
    setError(null)
    mermaid().then(async (m) => {
      try {
        const { svg } = await m.render(`mmd-${++counter}`, source)
        if (!cancelled && ref.current) ref.current.innerHTML = svg
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
  return <div ref={ref} className={`mermaid-host flex justify-center [&_svg]:max-w-full [&_svg]:h-auto ${className}`} />
}
