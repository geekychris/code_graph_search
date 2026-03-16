import React, { useState, useEffect, useCallback, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search, Filter, ChevronDown, ChevronUp, ArrowUpDown } from 'lucide-react'
import { searchCode, listRepos } from '../api/client'
import type { Element, SearchParams, SearchResult, ElementType, Language } from '../types'
import {
  getElementTypeBgClass,
  getElementTypeLabel,
  getLanguageBadgeColor,
  getLanguageLabel,
} from '../utils/elementColors'

interface Props {
  onSelectElement: (element: Element) => void
  onFileClick?: (filePath: string) => void
  initialQuery?: string
  initialFile?: string
}

const ELEMENT_TYPES: ElementType[] = [
  'CLASS', 'METHOD', 'FUNCTION', 'FIELD', 'INTERFACE', 'ENUM', 'STRUCT',
  'CONSTRUCTOR', 'COMMENT_DOC', 'MARKDOWN_DOCUMENT', 'CONFIG_FILE',
]

const LANGUAGES: Language[] = ['JAVA', 'GO', 'RUST', 'TYPESCRIPT', 'JAVASCRIPT', 'C', 'CPP']

const SORT_OPTIONS = [
  { value: 'relevance', label: 'Relevance' },
  { value: 'name', label: 'Name' },
  { value: 'file', label: 'File' },
  { value: 'line', label: 'Line' },
]

const PAGE_SIZE = 20

export default function SearchPanel({ onSelectElement, onFileClick, initialQuery = '', initialFile = '' }: Props) {
  const [query, setQuery] = useState(initialQuery)
  const [debouncedQuery, setDebouncedQuery] = useState(initialQuery)
  const [showFilters, setShowFilters] = useState(false)
  const [selectedTypes, setSelectedTypes] = useState<ElementType[]>([])
  const [selectedLangs, setSelectedLangs] = useState<Language[]>([])
  const [selectedRepos, setSelectedRepos] = useState<string[]>([])
  const [filePattern, setFilePattern] = useState(initialFile)
  const [sort, setSort] = useState('relevance')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc')
  const [page, setPage] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)

  // Debounce query as user types
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(query)
      setPage(0)
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  const { data: repos } = useQuery({
    queryKey: ['repos'],
    queryFn: listRepos,
  })

  const searchParams: SearchParams = {
    q: debouncedQuery || undefined,
    type: selectedTypes.length ? selectedTypes : undefined,
    lang: selectedLangs.length ? selectedLangs : undefined,
    repo: selectedRepos.length ? selectedRepos : undefined,
    file: filePattern || undefined,
    sort: sort !== 'relevance' ? sort : undefined,
    sortDir,
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
  }

  const { data, isLoading, isError, error } = useQuery<SearchResult>({
    queryKey: ['search', searchParams],
    queryFn: () => searchCode(searchParams),
    enabled: true,
    placeholderData: (prev: SearchResult | undefined) => prev,
  })

  // Keyboard shortcut: focus on /
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === '/' && document.activeElement?.tagName !== 'INPUT' && document.activeElement?.tagName !== 'TEXTAREA') {
        e.preventDefault()
        inputRef.current?.focus()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  useEffect(() => {
    if (initialQuery !== query) setQuery(initialQuery)
  }, [initialQuery])

  useEffect(() => {
    if (initialFile !== filePattern) {
      setFilePattern(initialFile)
      if (initialFile) setDebouncedQuery('')
    }
  }, [initialFile])

  const handleSubmit = useCallback((e: React.FormEvent) => {
    e.preventDefault()
    setDebouncedQuery(query)
    setPage(0)
  }, [query])

  const toggleType = (t: ElementType) =>
    setSelectedTypes((prev) => prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t])

  const toggleLang = (l: Language) =>
    setSelectedLangs((prev) => prev.includes(l) ? prev.filter((x) => x !== l) : [...prev, l])

  const toggleRepo = (id: string) =>
    setSelectedRepos((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])

  const totalPages = data ? Math.ceil(data.total / PAGE_SIZE) : 0

  return (
    <div className="flex flex-col h-full">
      {/* Search bar */}
      <form onSubmit={handleSubmit} className="flex gap-2 p-3 border-b border-slate-700">
        <div className="relative flex-1">
          <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            ref={inputRef}
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder='Search code... (press / to focus)'
            className="w-full pl-9 pr-3 py-2 bg-slate-800 border border-slate-600 rounded-lg text-sm text-slate-200 placeholder-slate-500 focus:outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500"
          />
        </div>
        <button
          type="submit"
          className="px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white rounded-lg text-sm font-medium transition-colors"
        >
          Search
        </button>
        <button
          type="button"
          onClick={() => setShowFilters((v) => !v)}
          className={`px-3 py-2 rounded-lg text-sm flex items-center gap-1 transition-colors ${
            showFilters ? 'bg-slate-600 text-white' : 'bg-slate-800 border border-slate-600 text-slate-400 hover:text-white'
          }`}
        >
          <Filter size={14} />
          {showFilters ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
        </button>
      </form>

      {/* Filters */}
      {showFilters && (
        <div className="p-3 border-b border-slate-700 bg-slate-800/50 space-y-3">
          {/* Element types */}
          <div>
            <div className="text-xs text-slate-400 mb-1.5 font-medium uppercase tracking-wide">Element Types</div>
            <div className="flex flex-wrap gap-1.5">
              {ELEMENT_TYPES.map((t) => (
                <button
                  key={t}
                  onClick={() => toggleType(t)}
                  className={`px-2 py-0.5 rounded text-xs border transition-colors ${
                    selectedTypes.includes(t)
                      ? getElementTypeBgClass(t) + ' border-opacity-100'
                      : 'bg-slate-700/50 text-slate-400 border-slate-600 hover:border-slate-500'
                  }`}
                >
                  {getElementTypeLabel(t)}
                </button>
              ))}
            </div>
          </div>

          {/* Languages */}
          <div>
            <div className="text-xs text-slate-400 mb-1.5 font-medium uppercase tracking-wide">Languages</div>
            <div className="flex flex-wrap gap-1.5">
              {LANGUAGES.map((l) => (
                <button
                  key={l}
                  onClick={() => toggleLang(l)}
                  className={`px-2 py-0.5 rounded text-xs border transition-colors ${
                    selectedLangs.includes(l)
                      ? getLanguageBadgeColor(l)
                      : 'bg-slate-700/50 text-slate-400 border-slate-600 hover:border-slate-500'
                  }`}
                >
                  {getLanguageLabel(l)}
                </button>
              ))}
            </div>
          </div>

          {/* Repos */}
          {repos && repos.length > 0 && (
            <div>
              <div className="text-xs text-slate-400 mb-1.5 font-medium uppercase tracking-wide">Repositories</div>
              <div className="flex flex-wrap gap-1.5">
                {repos.map((r) => (
                  <button
                    key={r.id}
                    onClick={() => toggleRepo(r.id)}
                    className={`px-2 py-0.5 rounded text-xs border transition-colors ${
                      selectedRepos.includes(r.id)
                        ? 'bg-amber-500/20 text-amber-300 border-amber-500/30'
                        : 'bg-slate-700/50 text-slate-400 border-slate-600 hover:border-slate-500'
                    }`}
                  >
                    {r.name}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* File pattern + sort */}
          <div className="flex gap-2">
            <input
              type="text"
              value={filePattern}
              onChange={(e) => setFilePattern(e.target.value)}
              placeholder="File path pattern..."
              className="flex-1 px-3 py-1.5 bg-slate-700 border border-slate-600 rounded text-sm text-slate-300 placeholder-slate-500 focus:outline-none focus:border-blue-500"
            />
            <select
              value={sort}
              onChange={(e) => setSort(e.target.value)}
              className="px-2 py-1.5 bg-slate-700 border border-slate-600 rounded text-sm text-slate-300 focus:outline-none focus:border-blue-500"
            >
              {SORT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>{o.label}</option>
              ))}
            </select>
            <button
              onClick={() => setSortDir((d) => d === 'asc' ? 'desc' : 'asc')}
              className="px-2 py-1.5 bg-slate-700 border border-slate-600 rounded text-slate-400 hover:text-white transition-colors"
              title={sortDir === 'asc' ? 'Ascending' : 'Descending'}
            >
              <ArrowUpDown size={14} />
            </button>
          </div>
        </div>
      )}

      {/* Results */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="flex items-center justify-center py-16">
            <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
          </div>
        )}

        {isError && (
          <div className="p-4 text-red-400 text-sm">
            Error: {(error as Error).message}
          </div>
        )}

        {!isLoading && !isError && data && data.items.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 text-slate-500">
            <Search size={40} className="mb-3 opacity-30" />
            <p className="text-sm">No results found</p>
            <p className="text-xs mt-1">Try adjusting your search or filters</p>
          </div>
        )}

        {!isLoading && !data && !isError && (
          <div className="flex flex-col items-center justify-center py-16 text-slate-600">
            <Search size={40} className="mb-3 opacity-30" />
            <p className="text-sm">Enter a query to search</p>
            <p className="text-xs mt-1">Press / to focus the search bar</p>
          </div>
        )}

        {data && data.items.length > 0 && (
          <>
            <div className="px-3 py-2 text-xs text-slate-500 border-b border-slate-800">
              {data.total.toLocaleString()} result{data.total !== 1 ? 's' : ''}
              {page > 0 && ` — page ${page + 1} of ${totalPages}`}
            </div>
            <div className="divide-y divide-slate-800">
              {data.items.map((item) => (
                <SearchResultItem
                  key={item.id}
                  item={item}
                  onClick={() => onSelectElement(item)}
                  onFileClick={onFileClick}
                />
              ))}
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="flex items-center justify-between px-3 py-3 border-t border-slate-800">
                <button
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                  className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 disabled:opacity-40 disabled:cursor-not-allowed rounded text-sm text-slate-300 transition-colors"
                >
                  Previous
                </button>
                <span className="text-xs text-slate-500">
                  {page + 1} / {totalPages}
                </span>
                <button
                  onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                  disabled={page >= totalPages - 1}
                  className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 disabled:opacity-40 disabled:cursor-not-allowed rounded text-sm text-slate-300 transition-colors"
                >
                  Next
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

interface ResultItemProps {
  item: Element
  onClick: () => void
  onFileClick?: (path: string) => void
}

function SearchResultItem({ item, onClick, onFileClick }: ResultItemProps) {
  const snippetLines = item.snippet
    ? item.snippet.split('\n').slice(0, 2).join('\n')
    : null

  return (
    <button
      onClick={onClick}
      className="w-full text-left px-3 py-2.5 hover:bg-slate-800/60 transition-colors group"
    >
      <div className="flex items-start gap-2 mb-1">
        <span className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium border ${getElementTypeBgClass(item.elementType)}`}>
          {getElementTypeLabel(item.elementType)}
        </span>
        <span className="font-medium text-slate-200 text-sm leading-tight break-all">{item.name}</span>
        <span className={`ml-auto shrink-0 px-1.5 py-0.5 rounded text-[10px] border ${getLanguageBadgeColor(item.language)}`}>
          {getLanguageLabel(item.language)}
        </span>
      </div>

      {item.qualifiedName && item.qualifiedName !== item.name && (
        <div className="text-xs text-slate-500 mb-1 ml-0 truncate">{item.qualifiedName}</div>
      )}

      <button
        onClick={(e) => {
          e.stopPropagation()
          onFileClick?.(item.filePath)
        }}
        className="text-[11px] font-mono text-slate-500 hover:text-blue-400 transition-colors mb-1 block text-left truncate max-w-full"
        title={item.filePath}
      >
        {item.filePath}:{item.lineStart}
        {item.lineEnd !== item.lineStart && `-${item.lineEnd}`}
      </button>

      {snippetLines && (
        <pre className="text-[11px] font-mono text-slate-500 bg-slate-900/60 rounded px-2 py-1 overflow-hidden whitespace-pre-wrap break-all line-clamp-2">
          {snippetLines}
        </pre>
      )}
    </button>
  )
}
