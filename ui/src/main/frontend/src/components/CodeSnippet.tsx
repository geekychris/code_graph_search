import React, { useEffect, useRef, useCallback } from 'react'
import { EditorState } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLine, Decoration, ViewPlugin } from '@codemirror/view'
import type { DecorationSet } from '@codemirror/view'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'
import { java } from '@codemirror/lang-java'
import { javascript } from '@codemirror/lang-javascript'
import { cpp } from '@codemirror/lang-cpp'
import { rust } from '@codemirror/lang-rust'
import { oneDark } from '@codemirror/theme-one-dark'
import type { Language } from '../types'
import { Copy, ExternalLink } from 'lucide-react'

interface Props {
  code: string
  language: Language
  highlightLines?: [number, number]
  startLine?: number
  filePath?: string
}

function getLanguageExtension(lang: Language) {
  switch (lang) {
    case 'JAVA': return java()
    case 'TYPESCRIPT': return javascript({ typescript: true })
    case 'JAVASCRIPT': return javascript()
    case 'CPP':
    case 'C': return cpp()
    case 'RUST': return rust()
    default: return null
  }
}

function highlightLinesPlugin(highlightLines: [number, number] | undefined, startLine: number) {
  if (!highlightLines) return []
  return [
    ViewPlugin.fromClass(
      class {
        decorations: DecorationSet
        constructor(view: EditorView) {
          this.decorations = this.buildDecorations(view)
        }
        update() {}
        buildDecorations(view: EditorView): DecorationSet {
          if (!highlightLines) return Decoration.none
          const [hl1, hl2] = highlightLines
          const ranges: ReturnType<Decoration['range']>[] = []
          for (let ln = hl1; ln <= hl2; ln++) {
            const docLine = ln - startLine + 1
            if (docLine < 1 || docLine > view.state.doc.lines) continue
            const line = view.state.doc.line(docLine)
            ranges.push(
              Decoration.line({ class: 'cm-highlighted-line' }).range(line.from) as ReturnType<Decoration['range']>
            )
          }
          return Decoration.set(ranges as any, true)
        }
      },
      { decorations: (v) => v.decorations }
    ),
  ]
}

export default function CodeSnippet({ code, language, highlightLines, startLine = 1, filePath }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const viewRef = useRef<EditorView | null>(null)

  useEffect(() => {
    if (!containerRef.current) return

    const langExt = getLanguageExtension(language)
    const extensions = [
      oneDark,
      lineNumbers({ formatNumber: (n) => String(n + startLine - 1) }),
      syntaxHighlighting(defaultHighlightStyle),
      highlightActiveLine(),
      EditorView.editable.of(false),
      EditorView.lineWrapping,
      ...highlightLinesPlugin(highlightLines, startLine),
    ]
    if (langExt) extensions.push(langExt)

    const state = EditorState.create({ doc: code, extensions })
    const view = new EditorView({ state, parent: containerRef.current })
    viewRef.current = view

    return () => {
      view.destroy()
      viewRef.current = null
    }
  }, [code, language, highlightLines, startLine])

  const copyCode = useCallback(() => {
    navigator.clipboard.writeText(code).catch(() => {})
  }, [code])

  const openInEditor = useCallback(() => {
    if (filePath && highlightLines) {
      window.open(`vscode://file/${filePath}:${highlightLines[0]}`, '_blank')
    } else if (filePath) {
      window.open(`vscode://file/${filePath}`, '_blank')
    }
  }, [filePath, highlightLines])

  return (
    <div className="relative rounded-lg overflow-hidden border border-slate-700">
      <div className="absolute top-2 right-2 flex gap-1 z-10">
        <button
          onClick={copyCode}
          className="p-1.5 rounded bg-slate-700/80 hover:bg-slate-600 text-slate-300 hover:text-white transition-colors"
          title="Copy code"
        >
          <Copy size={13} />
        </button>
        {filePath && (
          <button
            onClick={openInEditor}
            className="p-1.5 rounded bg-slate-700/80 hover:bg-slate-600 text-slate-300 hover:text-white transition-colors"
            title="Open in VS Code"
          >
            <ExternalLink size={13} />
          </button>
        )}
      </div>
      <div ref={containerRef} />
    </div>
  )
}
