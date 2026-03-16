import React, { useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ChevronDown, ChevronRight, Copy, Network, X,
  MapPin, Code2, FileText, Link, GitBranch, MessageSquare,
} from 'lucide-react'
import {
  getElement, getSnippet, getParent, getChildren, getSiblings,
  getCallers, getCallees, getUsages, getSuperclass, getInterfaces,
  getSubclasses, getImplementors, getComments, getAnnotations,
  getMethods, getFields,
} from '../api/client'
import type { Element } from '../types'
import { getElementTypeBgClass, getElementTypeLabel, getLanguageBadgeColor, getLanguageLabel } from '../utils/elementColors'
import CodeSnippet from './CodeSnippet'

interface Props {
  elementId: string
  onNavigate: (element: Element) => void
  onOpenInGraph: (element: Element) => void
  onSetConnectivityA?: (element: Element) => void
  onSetConnectivityB?: (element: Element) => void
  onClose: () => void
}

type RelTab = 'structure' | 'hierarchy' | 'calls' | 'references' | 'docs'

function Section({ title, icon, defaultOpen = true, children }: {
  title: string
  icon?: React.ReactNode
  defaultOpen?: boolean
  children: React.ReactNode
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div className="border-b border-slate-700">
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center gap-2 px-4 py-2.5 hover:bg-slate-800/40 transition-colors text-left"
      >
        {open ? <ChevronDown size={14} className="text-slate-500" /> : <ChevronRight size={14} className="text-slate-500" />}
        {icon && <span className="text-slate-400">{icon}</span>}
        <span className="text-xs font-semibold text-slate-400 uppercase tracking-wide">{title}</span>
      </button>
      {open && <div className="px-4 pb-3">{children}</div>}
    </div>
  )
}

function ElementChip({ element, onClick }: { element: Element; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="flex items-center gap-1.5 px-2 py-1 bg-slate-800 hover:bg-slate-700 rounded border border-slate-700 hover:border-slate-600 transition-colors text-left w-full"
    >
      <span className={`shrink-0 px-1 py-0.5 rounded text-[9px] font-medium border ${getElementTypeBgClass(element.elementType)}`}>
        {getElementTypeLabel(element.elementType)}
      </span>
      <span className="text-xs text-slate-300 truncate">{element.name}</span>
    </button>
  )
}

function ElementList({ elements, onNavigate, loading, empty = 'None' }: {
  elements?: Element[]
  onNavigate: (e: Element) => void
  loading?: boolean
  empty?: string
}) {
  if (loading) return <div className="text-xs text-slate-500 py-1">Loading...</div>
  if (!elements || elements.length === 0) return <div className="text-xs text-slate-600 py-1">{empty}</div>
  return (
    <div className="space-y-1 mt-1">
      {elements.slice(0, 50).map((el) => (
        <ElementChip key={el.id} element={el} onClick={() => onNavigate(el)} />
      ))}
      {elements.length > 50 && (
        <div className="text-xs text-slate-600">...and {elements.length - 50} more</div>
      )}
    </div>
  )
}

export default function ElementDetail({ elementId, onNavigate, onOpenInGraph, onSetConnectivityA, onSetConnectivityB, onClose }: Props) {
  const [relTab, setRelTab] = useState<RelTab>('structure')

  const { data: element, isLoading, isError } = useQuery({
    queryKey: ['element', elementId],
    queryFn: () => getElement(elementId),
    enabled: !!elementId,
  })

  const { data: snippet } = useQuery({
    queryKey: ['snippet', elementId],
    queryFn: () => getSnippet(elementId, 5),
    enabled: !!elementId,
  })

  const { data: parent, isLoading: parentLoading } = useQuery({
    queryKey: ['parent', elementId],
    queryFn: () => getParent(elementId),
    enabled: !!elementId && relTab === 'structure',
  })

  const { data: children, isLoading: childrenLoading } = useQuery({
    queryKey: ['children', elementId],
    queryFn: () => getChildren(elementId),
    enabled: !!elementId && relTab === 'structure',
  })

  const { data: siblings, isLoading: siblingsLoading } = useQuery({
    queryKey: ['siblings', elementId],
    queryFn: () => getSiblings(elementId),
    enabled: !!elementId && relTab === 'structure',
  })

  const { data: superclass, isLoading: superclassLoading } = useQuery({
    queryKey: ['superclass', elementId],
    queryFn: () => getSuperclass(elementId),
    enabled: !!elementId && relTab === 'hierarchy',
  })

  const { data: interfaces, isLoading: interfacesLoading } = useQuery({
    queryKey: ['interfaces', elementId],
    queryFn: () => getInterfaces(elementId),
    enabled: !!elementId && relTab === 'hierarchy',
  })

  const { data: subclasses, isLoading: subclassesLoading } = useQuery({
    queryKey: ['subclasses', elementId],
    queryFn: () => getSubclasses(elementId),
    enabled: !!elementId && relTab === 'hierarchy',
  })

  const { data: implementors, isLoading: implementorsLoading } = useQuery({
    queryKey: ['implementors', elementId],
    queryFn: () => getImplementors(elementId),
    enabled: !!elementId && relTab === 'hierarchy',
  })

  const { data: callers, isLoading: callersLoading } = useQuery({
    queryKey: ['callers', elementId],
    queryFn: () => getCallers(elementId),
    enabled: !!elementId && relTab === 'calls',
  })

  const { data: callees, isLoading: calleesLoading } = useQuery({
    queryKey: ['callees', elementId],
    queryFn: () => getCallees(elementId),
    enabled: !!elementId && relTab === 'calls',
  })

  const { data: usages, isLoading: usagesLoading } = useQuery({
    queryKey: ['usages', elementId],
    queryFn: () => getUsages(elementId),
    enabled: !!elementId && relTab === 'references',
  })

  const { data: fields, isLoading: fieldsLoading } = useQuery({
    queryKey: ['fields', elementId],
    queryFn: () => getFields(elementId),
    enabled: !!elementId && relTab === 'structure',
  })

  const { data: methods, isLoading: methodsLoading } = useQuery({
    queryKey: ['methods', elementId],
    queryFn: () => getMethods(elementId),
    enabled: !!elementId && relTab === 'structure',
  })

  const { data: comments, isLoading: commentsLoading } = useQuery({
    queryKey: ['comments', elementId],
    queryFn: () => getComments(elementId),
    enabled: !!elementId && relTab === 'docs',
  })

  const { data: annotations, isLoading: annotationsLoading } = useQuery({
    queryKey: ['annotations', elementId],
    queryFn: () => getAnnotations(elementId),
    enabled: !!elementId && relTab === 'docs',
  })

  const copyId = useCallback(() => {
    navigator.clipboard.writeText(elementId).catch(() => {})
  }, [elementId])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-40">
        <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  if (isError || !element) {
    return (
      <div className="p-4 text-red-400 text-sm">Failed to load element</div>
    )
  }

  const relTabs: { id: RelTab; label: string }[] = [
    { id: 'structure', label: 'Structure' },
    { id: 'hierarchy', label: 'Hierarchy' },
    { id: 'calls', label: 'Calls' },
    { id: 'references', label: 'Refs' },
    { id: 'docs', label: 'Docs' },
  ]

  return (
    <div className="flex flex-col h-full overflow-y-auto text-sm">
      {/* Header */}
      <div className="sticky top-0 z-10 bg-slate-900 border-b border-slate-700 px-4 py-3">
        <div className="flex items-start justify-between gap-2 mb-2">
          <div className="flex items-center gap-2 flex-wrap min-w-0">
            <span className={`shrink-0 px-2 py-0.5 rounded text-xs font-medium border ${getElementTypeBgClass(element.elementType)}`}>
              {getElementTypeLabel(element.elementType)}
            </span>
            <span className={`shrink-0 px-2 py-0.5 rounded text-xs border ${getLanguageBadgeColor(element.language)}`}>
              {getLanguageLabel(element.language)}
            </span>
            {element.visibility && (
              <span className="shrink-0 px-2 py-0.5 rounded text-xs bg-slate-700 text-slate-400 border border-slate-600">
                {element.visibility}
              </span>
            )}
            {element.modifiers?.map((m) => (
              <span key={m} className="shrink-0 px-2 py-0.5 rounded text-xs bg-slate-700 text-slate-400 border border-slate-600">
                {m}
              </span>
            ))}
          </div>
          <button onClick={onClose} className="shrink-0 p-1 text-slate-500 hover:text-slate-300 transition-colors">
            <X size={16} />
          </button>
        </div>
        <h2 className="text-base font-semibold text-white break-all">{element.name}</h2>
        {element.qualifiedName && element.qualifiedName !== element.name && (
          <div className="text-xs text-slate-500 mt-0.5 break-all">{element.qualifiedName}</div>
        )}
        <div className="flex flex-wrap gap-1.5 mt-2">
          <button
            onClick={() => onOpenInGraph(element)}
            className="flex items-center gap-1.5 px-2.5 py-1 bg-blue-600/20 hover:bg-blue-600/30 text-blue-400 rounded border border-blue-600/30 text-xs transition-colors"
          >
            <Network size={12} />
            Graph
          </button>
          <button
            onClick={copyId}
            className="flex items-center gap-1.5 px-2.5 py-1 bg-slate-700 hover:bg-slate-600 text-slate-400 rounded border border-slate-600 text-xs transition-colors"
          >
            <Copy size={12} />
            ID
          </button>
          {onSetConnectivityA && (
            <button
              onClick={() => onSetConnectivityA(element)}
              className="flex items-center gap-1.5 px-2.5 py-1 bg-amber-600/20 hover:bg-amber-600/30 text-amber-400 rounded border border-amber-600/30 text-xs transition-colors"
            >
              <GitBranch size={12} />
              Set A
            </button>
          )}
          {onSetConnectivityB && (
            <button
              onClick={() => onSetConnectivityB(element)}
              className="flex items-center gap-1.5 px-2.5 py-1 bg-amber-600/20 hover:bg-amber-600/30 text-amber-400 rounded border border-amber-600/30 text-xs transition-colors"
            >
              <GitBranch size={12} />
              Set B
            </button>
          )}
        </div>
      </div>

      {/* Location */}
      <Section title="Location" icon={<MapPin size={13} />}>
        <div className="space-y-1 text-xs">
          <div className="text-slate-500">Repo: <span className="text-slate-300">{element.repoId}</span></div>
          <div className="text-slate-500 font-mono break-all text-[11px]">{element.filePath}</div>
          <div className="text-slate-500">Lines: <span className="text-slate-300">{element.lineStart}–{element.lineEnd}</span></div>
        </div>
      </Section>

      {/* Signature */}
      {element.signature && (
        <Section title="Signature" icon={<Code2 size={13} />}>
          <pre className="text-xs font-mono text-green-300 bg-slate-800/60 rounded p-2 whitespace-pre-wrap break-all">
            {element.signature}
          </pre>
          {element.returnType && (
            <div className="mt-1 text-xs text-slate-500">
              Returns: <span className="font-mono text-yellow-300">{element.returnType}</span>
            </div>
          )}
        </Section>
      )}

      {/* Documentation */}
      {element.docComment && (
        <Section title="Documentation" icon={<FileText size={13} />}>
          <div className="text-xs text-slate-300 whitespace-pre-wrap leading-relaxed bg-slate-800/40 rounded p-2 font-mono">
            {element.docComment}
          </div>
        </Section>
      )}

      {/* Code Snippet */}
      <Section title="Code Snippet" icon={<Code2 size={13} />}>
        {snippet ? (
          <CodeSnippet
            code={snippet.snippet}
            language={element.language}
            highlightLines={[snippet.lineStart, snippet.lineEnd]}
            startLine={snippet.lineStart}
            filePath={snippet.filePath}
          />
        ) : element.snippet ? (
          <CodeSnippet
            code={element.snippet}
            language={element.language}
            highlightLines={[element.lineStart, element.lineEnd]}
            startLine={element.lineStart}
            filePath={element.filePath}
          />
        ) : (
          <div className="text-xs text-slate-600">No snippet available</div>
        )}
      </Section>

      {/* Relationships */}
      <Section title="Relationships" icon={<Link size={13} />}>
        {/* Tab bar */}
        <div className="flex gap-0.5 mb-3 -mx-1 bg-slate-800/60 p-0.5 rounded">
          {relTabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setRelTab(tab.id)}
              className={`flex-1 py-1 text-[11px] rounded transition-colors ${
                relTab === tab.id
                  ? 'bg-slate-600 text-white'
                  : 'text-slate-500 hover:text-slate-300'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {relTab === 'structure' && (
          <div className="space-y-3">
            {parent && (
              <div>
                <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Parent</div>
                <ElementChip element={parent} onClick={() => onNavigate(parent)} />
              </div>
            )}
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Methods</div>
              <ElementList elements={methods} onNavigate={onNavigate} loading={methodsLoading} empty="No methods" />
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Fields</div>
              <ElementList elements={fields} onNavigate={onNavigate} loading={fieldsLoading} empty="No fields" />
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Children</div>
              <ElementList elements={children} onNavigate={onNavigate} loading={childrenLoading} empty="No children" />
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Siblings</div>
              <ElementList elements={siblings} onNavigate={onNavigate} loading={siblingsLoading} empty="No siblings" />
            </div>
          </div>
        )}

        {relTab === 'hierarchy' && (
          <div className="space-y-3">
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Superclass</div>
              {superclassLoading ? (
                <div className="text-xs text-slate-500">Loading...</div>
              ) : superclass ? (
                <ElementChip element={superclass} onClick={() => onNavigate(superclass)} />
              ) : (
                <div className="text-xs text-slate-600">None</div>
              )}
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Interfaces</div>
              <ElementList elements={interfaces} onNavigate={onNavigate} loading={interfacesLoading} empty="None" />
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Subclasses</div>
              <ElementList elements={subclasses} onNavigate={onNavigate} loading={subclassesLoading} empty="None" />
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Implementors</div>
              <ElementList elements={implementors} onNavigate={onNavigate} loading={implementorsLoading} empty="None" />
            </div>
          </div>
        )}

        {relTab === 'calls' && (
          <div className="space-y-3">
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Callers</div>
              <ElementList elements={callers} onNavigate={onNavigate} loading={callersLoading} empty="No callers" />
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Callees</div>
              <ElementList elements={callees} onNavigate={onNavigate} loading={calleesLoading} empty="No callees" />
            </div>
          </div>
        )}

        {relTab === 'references' && (
          <div className="space-y-3">
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Usages</div>
              <ElementList elements={usages} onNavigate={onNavigate} loading={usagesLoading} empty="No usages found" />
            </div>
          </div>
        )}

        {relTab === 'docs' && (
          <div className="space-y-3">
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Comments</div>
              <ElementList elements={comments} onNavigate={onNavigate} loading={commentsLoading} empty="No comments" />
            </div>
            <div>
              <div className="text-[10px] uppercase text-slate-600 mb-1 tracking-wide">Annotations</div>
              <ElementList elements={annotations} onNavigate={onNavigate} loading={annotationsLoading} empty="No annotations" />
            </div>
          </div>
        )}
      </Section>

      {/* Metadata */}
      {element.metadata && Object.keys(element.metadata).length > 0 && (
        <Section title="Metadata" icon={<MessageSquare size={13} />} defaultOpen={false}>
          <div className="space-y-1">
            {Object.entries(element.metadata).map(([k, v]) => (
              <div key={k} className="flex gap-2 text-xs">
                <span className="text-slate-500 shrink-0 font-mono">{k}:</span>
                <span className="text-slate-400 break-all font-mono">{v}</span>
              </div>
            ))}
          </div>
        </Section>
      )}
    </div>
  )
}
