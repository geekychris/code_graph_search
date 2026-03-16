import React, { useState, useCallback, useEffect } from 'react'
import { Search, Network, Database, GitBranch, ChevronLeft, ChevronRight, X, Menu } from 'lucide-react'
import SearchPanel from './components/SearchPanel'
import ElementDetail from './components/ElementDetail'
import GraphView from './components/GraphView'
import ConnectivityExplorer from './components/ConnectivityExplorer'
import RepoManager from './components/RepoManager'
import FileTree from './components/FileTree'
import type { Element } from './types'

type Tab = 'search' | 'graph' | 'connectivity' | 'repos'

interface NavState {
  selectedElementId: string | null
  activeTab: Tab
  searchQuery: string
  searchFile: string
  graphRootId: string | null
}

// Navigation history entry
interface HistoryEntry {
  elementId: string | null
  tab: Tab
}

export default function App() {
  const [activeTab, setActiveTab] = useState<Tab>('search')
  const [selectedElementId, setSelectedElementId] = useState<string | null>(null)
  const [detailOpen, setDetailOpen] = useState(false)
  const [graphRootId, setGraphRootId] = useState<string | null>(null)
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const [searchQuery, setSearchQuery] = useState('')
  const [searchFile, setSearchFile] = useState('')
  const [history, setHistory] = useState<HistoryEntry[]>([])
  const [historyIndex, setHistoryIndex] = useState(-1)
  const [connectivityA, setConnectivityA] = useState<Element | null>(null)
  const [connectivityB, setConnectivityB] = useState<Element | null>(null)

  // Hash-based state persistence
  useEffect(() => {
    const hash = window.location.hash.slice(1)
    if (hash) {
      try {
        const state: Partial<NavState> = JSON.parse(decodeURIComponent(hash))
        if (state.activeTab) setActiveTab(state.activeTab)
        if (state.selectedElementId) {
          setSelectedElementId(state.selectedElementId)
          setDetailOpen(true)
        }
        if (state.searchQuery) setSearchQuery(state.searchQuery)
        if (state.searchFile) setSearchFile(state.searchFile)
        if (state.graphRootId) setGraphRootId(state.graphRootId)
      } catch (_) {}
    }
  }, [])

  const updateHash = useCallback((state: Partial<NavState>) => {
    const current: Partial<NavState> = { activeTab, selectedElementId, searchQuery, searchFile, graphRootId }
    const next = { ...current, ...state }
    window.history.replaceState(null, '', `#${encodeURIComponent(JSON.stringify(next))}`)
  }, [activeTab, selectedElementId, searchQuery, searchFile, graphRootId])

  const pushHistory = useCallback((entry: HistoryEntry) => {
    setHistory((prev) => {
      const truncated = prev.slice(0, historyIndex + 1)
      return [...truncated, entry]
    })
    setHistoryIndex((prev) => prev + 1)
  }, [historyIndex])

  const handleSelectElement = useCallback((element: Element) => {
    setSelectedElementId(element.id)
    setDetailOpen(true)
    pushHistory({ elementId: element.id, tab: activeTab })
    updateHash({ selectedElementId: element.id })
  }, [activeTab, pushHistory, updateHash])

  const handleNavigateElement = useCallback((element: Element) => {
    setSelectedElementId(element.id)
    pushHistory({ elementId: element.id, tab: activeTab })
    updateHash({ selectedElementId: element.id })
  }, [activeTab, pushHistory, updateHash])

  const handleOpenInGraph = useCallback((element: Element) => {
    setGraphRootId(element.id)
    setActiveTab('graph')
    updateHash({ graphRootId: element.id, activeTab: 'graph' })
  }, [updateHash])

  const handleSetConnectivityA = useCallback((element: Element) => {
    setConnectivityA(element)
    setActiveTab('connectivity')
    updateHash({ activeTab: 'connectivity' })
  }, [updateHash])

  const handleSetConnectivityB = useCallback((element: Element) => {
    setConnectivityB(element)
    setActiveTab('connectivity')
    updateHash({ activeTab: 'connectivity' })
  }, [updateHash])

  const handleFileClick = useCallback((filePath: string) => {
    setSearchFile(filePath)
    setSearchQuery('')
    setActiveTab('search')
    updateHash({ searchFile: filePath, activeTab: 'search' })
  }, [updateHash])

  const handleCloseDetail = useCallback(() => {
    setDetailOpen(false)
    setSelectedElementId(null)
  }, [])

  const handleTabChange = useCallback((tab: Tab) => {
    setActiveTab(tab)
    updateHash({ activeTab: tab })
  }, [updateHash])

  const canGoBack = historyIndex > 0
  const canGoForward = historyIndex < history.length - 1

  const goBack = useCallback(() => {
    if (!canGoBack) return
    const newIndex = historyIndex - 1
    setHistoryIndex(newIndex)
    const entry = history[newIndex]
    setSelectedElementId(entry.elementId)
    setDetailOpen(!!entry.elementId)
    setActiveTab(entry.tab)
  }, [canGoBack, historyIndex, history])

  const goForward = useCallback(() => {
    if (!canGoForward) return
    const newIndex = historyIndex + 1
    setHistoryIndex(newIndex)
    const entry = history[newIndex]
    setSelectedElementId(entry.elementId)
    setDetailOpen(!!entry.elementId)
    setActiveTab(entry.tab)
  }, [canGoForward, historyIndex, history])

  const tabs: { id: Tab; label: string; icon: React.ReactNode }[] = [
    { id: 'search', label: 'Search', icon: <Search size={15} /> },
    { id: 'graph', label: 'Graph', icon: <Network size={15} /> },
    { id: 'connectivity', label: 'Connectivity', icon: <GitBranch size={15} /> },
    { id: 'repos', label: 'Repos', icon: <Database size={15} /> },
  ]

  return (
    <div className="flex flex-col h-screen bg-slate-900 text-slate-200 overflow-hidden">
      {/* Top bar */}
      <div className="flex items-center gap-3 px-4 py-2.5 border-b border-slate-700 bg-slate-900 shrink-0">
        <button
          onClick={() => setSidebarOpen((v) => !v)}
          className="p-1.5 text-slate-500 hover:text-white transition-colors"
        >
          <Menu size={16} />
        </button>
        <div className="flex items-center gap-2">
          <Network size={18} className="text-blue-400" />
          <span className="font-semibold text-slate-200 text-sm">Code Graph Search</span>
        </div>

        {/* Nav history buttons */}
        <div className="flex gap-1 ml-2">
          <button
            onClick={goBack}
            disabled={!canGoBack}
            className="p-1.5 text-slate-500 hover:text-white disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            title="Back"
          >
            <ChevronLeft size={15} />
          </button>
          <button
            onClick={goForward}
            disabled={!canGoForward}
            className="p-1.5 text-slate-500 hover:text-white disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
            title="Forward"
          >
            <ChevronRight size={15} />
          </button>
        </div>

        {/* Tab bar */}
        <div className="flex gap-0.5 ml-4 bg-slate-800 rounded-lg p-0.5">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => handleTabChange(tab.id)}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded text-sm transition-colors ${
                activeTab === tab.id
                  ? 'bg-slate-600 text-white'
                  : 'text-slate-400 hover:text-white'
              }`}
            >
              {tab.icon}
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Main layout */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left sidebar - File Tree */}
        {sidebarOpen && (
          <div className="w-72 shrink-0 border-r border-slate-700 bg-slate-900 flex flex-col overflow-hidden">
            <div className="px-3 py-2 border-b border-slate-700">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Explorer</span>
            </div>
            <div className="flex-1 overflow-hidden">
              <FileTree onFileSelect={handleFileClick} />
            </div>
          </div>
        )}

        {/* Center - Main content */}
        <div className="flex-1 flex flex-col overflow-hidden">
          <div className="flex-1 overflow-hidden">
            <div className={`h-full ${activeTab === 'search' ? 'block' : 'hidden'}`}>
              <SearchPanel
                onSelectElement={handleSelectElement}
                onFileClick={handleFileClick}
                initialQuery={searchQuery}
                initialFile={searchFile}
              />
            </div>
            <div className={`h-full ${activeTab === 'graph' ? 'block' : 'hidden'}`}>
              <GraphView
                rootElementId={graphRootId || undefined}
                onSelectElement={handleSelectElement}
              />
            </div>
            <div className={`h-full ${activeTab === 'connectivity' ? 'block' : 'hidden'}`}>
              <ConnectivityExplorer
                onSelectElement={handleSelectElement}
                externalElementA={connectivityA}
                externalElementB={connectivityB}
              />
            </div>
            <div className={`h-full overflow-y-auto ${activeTab === 'repos' ? 'block' : 'hidden'}`}>
              <RepoManager />
            </div>
          </div>
        </div>

        {/* Right panel - Element Detail */}
        {detailOpen && selectedElementId && (
          <div className="w-96 shrink-0 border-l border-slate-700 bg-slate-900 flex flex-col overflow-hidden">
            <ElementDetail
              elementId={selectedElementId}
              onNavigate={handleNavigateElement}
              onOpenInGraph={handleOpenInGraph}
              onSetConnectivityA={handleSetConnectivityA}
              onSetConnectivityB={handleSetConnectivityB}
              onClose={handleCloseDetail}
            />
          </div>
        )}
      </div>
    </div>
  )
}
