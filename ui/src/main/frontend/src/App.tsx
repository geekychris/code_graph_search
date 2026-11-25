import React, { useState, useCallback, useEffect } from 'react'
import { Search, Network, Database, GitBranch, ChevronLeft, ChevronRight, X, Menu, Boxes, Lightbulb, BarChart3 } from 'lucide-react'
import SearchPanel from './components/SearchPanel'
import ElementDetail from './components/ElementDetail'
import GraphView from './components/GraphView'
import ConnectivityExplorer from './components/ConnectivityExplorer'
import RepoManager from './components/RepoManager'
import FileTree from './components/FileTree'
import PortfolioView from './components/portfolio/PortfolioView'
import RepoView, { type RepoTab } from './components/portfolio/RepoView'
import ConceptsView from './components/portfolio/ConceptsView'
import { PortfolioStats } from './components/portfolio/StatsView'
import AuthMenu from './components/AuthMenu'
import { useQuery } from '@tanstack/react-query'
import { getPortfolioOverview } from './api/client'
import { seedFamilies } from './components/portfolio/palette'
import type { Element } from './types'

type Tab = 'portfolio' | 'concepts' | 'stats' | 'search' | 'graph' | 'connectivity' | 'repos'

interface NavState {
  selectedElementId: string | null
  activeTab: Tab
  searchQuery: string
  searchFile: string
  graphRootId: string | null
  repoId: string | null
  repoTab: RepoTab
  archPath: string
  conceptSlug: string | null
}

const CODE_TABS: Tab[] = ['search', 'graph', 'connectivity']

// Navigation history entry
interface HistoryEntry {
  elementId: string | null
  tab: Tab
}

export default function App() {
  // Family colours must be assigned before any page draws them
  const overview = useQuery({ queryKey: ['portfolio-overview'], queryFn: getPortfolioOverview, staleTime: 60_000 })
  if (overview.data) seedFamilies(Object.keys(overview.data.families))
  const [activeTab, setActiveTab] = useState<Tab>('portfolio')
  const [repoId, setRepoId] = useState<string | null>(null)
  const [repoTab, setRepoTab] = useState<RepoTab>('overview')
  const [archPath, setArchPath] = useState('')
  const [conceptSlug, setConceptSlug] = useState<string | null>(null)
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

  // Hash-based state: restored on load and on browser back/forward
  const applyState = useCallback((state: Partial<NavState>) => {
    setActiveTab(state.activeTab || 'portfolio')
    setRepoId(state.repoId || null)
    setRepoTab(state.repoTab || 'overview')
    setArchPath(state.archPath || '')
    setConceptSlug(state.conceptSlug || null)
    if (state.selectedElementId) {
      setSelectedElementId(state.selectedElementId)
      setDetailOpen(true)
    }
    if (state.searchQuery) setSearchQuery(state.searchQuery)
    if (state.searchFile) setSearchFile(state.searchFile)
    if (state.graphRootId) setGraphRootId(state.graphRootId)
  }, [])

  useEffect(() => {
    const read = () => {
      const hash = window.location.hash.slice(1)
      if (!hash) return applyState({})
      try { applyState(JSON.parse(decodeURIComponent(hash))) } catch (_) {}
    }
    read()
    window.addEventListener('popstate', read)
    return () => window.removeEventListener('popstate', read)
  }, [applyState])

  const currentState = useCallback((): NavState => (
    { activeTab, selectedElementId, searchQuery, searchFile, graphRootId, repoId, repoTab, archPath, conceptSlug }
  ), [activeTab, selectedElementId, searchQuery, searchFile, graphRootId, repoId, repoTab, archPath, conceptSlug])

  const updateHash = useCallback((state: Partial<NavState>) => {
    const next = { ...currentState(), ...state }
    window.history.replaceState(null, '', `#${encodeURIComponent(JSON.stringify(next))}`)
  }, [currentState])

  /** Moves to a new place in the portfolio views, as a browser history entry. */
  const navigate = useCallback((state: Partial<NavState>) => {
    const next = { ...currentState(), ...state }
    window.history.pushState(null, '', `#${encodeURIComponent(JSON.stringify(next))}`)
    applyState(next)
  }, [currentState, applyState])

  const openRepo = useCallback((id: string) => navigate({ activeTab: 'portfolio', repoId: id, repoTab: 'overview', archPath: '' }), [navigate])
  const openConcept = useCallback((slug: string) => navigate({ activeTab: 'concepts', conceptSlug: slug }), [navigate])
  const openElementById = useCallback((id: string) => {
    setSelectedElementId(id)
    setDetailOpen(true)
    updateHash({ selectedElementId: id })
  }, [updateHash])

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
    { id: 'portfolio', label: 'Portfolio', icon: <Boxes size={15} /> },
    { id: 'concepts', label: 'Concepts', icon: <Lightbulb size={15} /> },
    { id: 'stats', label: 'Stats', icon: <BarChart3 size={15} /> },
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
              onClick={() => tab.id === 'portfolio' && activeTab === 'portfolio' ? navigate({ repoId: null }) : handleTabChange(tab.id)}
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
        <AuthMenu />
      </div>

      {/* Main layout */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left sidebar - File Tree */}
        {sidebarOpen && CODE_TABS.includes(activeTab) && (
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
            <div className={`h-full ${activeTab === 'portfolio' ? 'block' : 'hidden'}`}>
              {repoId ? (
                <RepoView repoId={repoId} tab={repoTab} archPath={archPath}
                  onTab={(t) => navigate({ repoTab: t })}
                  onArchPath={(p) => navigate({ repoTab: 'architecture', archPath: p })}
                  onBack={() => navigate({ repoId: null })}
                  onOpenRepo={openRepo} onOpenConcept={openConcept} onOpenElement={openElementById}
                  onSearchFile={(_, path) => handleFileClick(path)} />
              ) : (
                <PortfolioView onOpenRepo={openRepo} onOpenConcept={openConcept} />
              )}
            </div>
            <div className={`h-full ${activeTab === 'concepts' ? 'block' : 'hidden'}`}>
              <ConceptsView slug={conceptSlug} onSlug={(s) => navigate({ conceptSlug: s })}
                onOpenRepo={openRepo} onOpenElement={openElementById} />
            </div>
            <div className={`h-full ${activeTab === 'stats' ? 'block' : 'hidden'}`}>
              {activeTab === 'stats' && <PortfolioStats onOpenRepo={openRepo} />}
            </div>
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
