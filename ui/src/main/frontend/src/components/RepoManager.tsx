import React, { useState, useEffect } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  Database, Plus, Trash2, RefreshCw, ChevronDown, ChevronUp,
  CheckCircle, AlertCircle, Clock, Loader, Eye, FolderOpen,
} from 'lucide-react'
import { listRepos, deleteRepo, reindexRepo, getRepoStatus, apiFetch } from '../api/client'
import type { Repository, Language } from '../types'
import { getLanguageBadgeColor, getLanguageLabel } from '../utils/elementColors'

const ALL_LANGUAGES: Language[] = ['JAVA', 'GO', 'RUST', 'TYPESCRIPT', 'JAVASCRIPT', 'C', 'CPP', 'MARKDOWN', 'YAML', 'JSON']

function StatusBadge({ status }: { status: Repository['status'] }) {
  const configs = {
    READY: { cls: 'bg-green-500/20 text-green-300 border-green-500/30', icon: <CheckCircle size={11} />, label: 'Ready' },
    INDEXING: { cls: 'bg-yellow-500/20 text-yellow-300 border-yellow-500/30 animate-pulse', icon: <Loader size={11} className="animate-spin" />, label: 'Indexing' },
    WATCHING: { cls: 'bg-blue-500/20 text-blue-300 border-blue-500/30', icon: <Eye size={11} />, label: 'Watching' },
    ERROR: { cls: 'bg-red-500/20 text-red-300 border-red-500/30', icon: <AlertCircle size={11} />, label: 'Error' },
    PENDING: { cls: 'bg-slate-500/20 text-slate-400 border-slate-500/30', icon: <Clock size={11} />, label: 'Pending' },
  }
  const cfg = configs[status] || configs.PENDING
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs border ${cfg.cls}`}>
      {cfg.icon}
      {cfg.label}
    </span>
  )
}

function RepoPolling({ repoId, onUpdate }: { repoId: string; onUpdate: (repo: Repository) => void }) {
  useEffect(() => {
    const interval = setInterval(() => {
      getRepoStatus(repoId).then(onUpdate).catch(() => {})
    }, 3000)
    return () => clearInterval(interval)
  }, [repoId, onUpdate])
  return null
}

interface BrowseEntry {
  name: string
  path: string
  isDirectory: boolean
}

interface BrowseResult {
  currentPath: string
  entries: BrowseEntry[]
  isGitRepo: boolean
}

interface AddRepoFormData {
  id: string
  name: string
  path: string
  languages: Language[]
  description: string
}

function DirectoryPicker({ currentPath, onSelect, onClose }: {
  currentPath: string
  onSelect: (path: string) => void
  onClose: () => void
}) {
  const [browsePath, setBrowsePath] = useState(currentPath || '')
  const [manualPath, setManualPath] = useState('')

  const { data, isLoading } = useQuery<BrowseResult>({
    queryKey: ['browse', browsePath],
    queryFn: () => apiFetch<BrowseResult>(`/browse${browsePath ? `?path=${encodeURIComponent(browsePath)}` : ''}`),
  })

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="bg-slate-800 border border-slate-600 rounded-lg shadow-2xl w-full max-w-lg mx-4 flex flex-col max-h-[80vh]">
        <div className="px-4 py-3 border-b border-slate-700 flex items-center justify-between">
          <h3 className="text-sm font-medium text-slate-200">Choose Directory</h3>
          <button onClick={onClose} className="text-slate-500 hover:text-white text-lg leading-none">&times;</button>
        </div>

        {/* Current path display */}
        <div className="px-4 py-2 border-b border-slate-700/50 flex items-center gap-2">
          <span className="text-xs text-slate-500 shrink-0">Path:</span>
          <span className="text-xs font-mono text-slate-300 truncate">{data?.currentPath || browsePath || '~'}</span>
          {data?.isGitRepo && (
            <span className="shrink-0 px-1.5 py-0.5 rounded text-[10px] bg-amber-500/20 text-amber-300 border border-amber-500/30">
              git repo
            </span>
          )}
        </div>

        {/* Manual path input */}
        <div className="px-4 py-2 border-b border-slate-700/50 flex gap-2">
          <input
            type="text"
            value={manualPath}
            onChange={(e) => setManualPath(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && manualPath) setBrowsePath(manualPath) }}
            placeholder="Type path and press Enter..."
            className="flex-1 px-2 py-1 bg-slate-700 border border-slate-600 rounded text-xs text-slate-300 font-mono focus:outline-none focus:border-blue-500"
          />
          <button
            onClick={() => { if (manualPath) setBrowsePath(manualPath) }}
            className="px-2 py-1 bg-slate-700 hover:bg-slate-600 text-slate-400 rounded text-xs transition-colors"
          >
            Go
          </button>
        </div>

        {/* Directory listing */}
        <div className="flex-1 overflow-y-auto min-h-[200px]">
          {isLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader size={16} className="animate-spin text-slate-500" />
            </div>
          ) : (
            <div className="divide-y divide-slate-700/50">
              {data?.entries.map((entry) => (
                <button
                  key={entry.path}
                  onClick={() => {
                    setBrowsePath(entry.path)
                    setManualPath(entry.path)
                  }}
                  className="w-full text-left px-4 py-2 hover:bg-slate-700/60 flex items-center gap-2 transition-colors"
                >
                  <FolderOpen size={14} className={entry.name === '..' ? 'text-slate-500' : 'text-blue-400'} />
                  <span className="text-sm text-slate-300">{entry.name}</span>
                </button>
              ))}
              {data?.entries.length === (data?.entries[0]?.name === '..' ? 1 : 0) && (
                <div className="px-4 py-6 text-center text-xs text-slate-600">No subdirectories</div>
              )}
            </div>
          )}
        </div>

        {/* Actions */}
        <div className="px-4 py-3 border-t border-slate-700 flex justify-between items-center gap-2">
          <span className="text-[11px] text-slate-500 truncate flex-1">
            {data?.currentPath || ''}
          </span>
          <button
            onClick={onClose}
            className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded text-sm transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={() => { onSelect(data?.currentPath || browsePath); onClose() }}
            disabled={!data?.currentPath}
            className="px-3 py-1.5 bg-blue-600 hover:bg-blue-500 disabled:opacity-50 text-white rounded text-sm transition-colors"
          >
            Select
          </button>
        </div>
      </div>
    </div>
  )
}

export default function RepoManager() {
  const [showAddForm, setShowAddForm] = useState(false)
  const [showDirPicker, setShowDirPicker] = useState(false)
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null)
  const [formData, setFormData] = useState<AddRepoFormData>({
    id: '', name: '', path: '', languages: [], description: '',
  })
  const [repoStatuses, setRepoStatuses] = useState<Map<string, Repository>>(new Map())
  const queryClient = useQueryClient()

  const { data: repos, isLoading, isError } = useQuery({
    queryKey: ['repos'],
    queryFn: listRepos,
    refetchInterval: 10_000,
  })

  const addMutation = useMutation({
    mutationFn: (data: AddRepoFormData) => {
      // Backend expects { id, name, path, languages, description }
      const body: Record<string, unknown> = {
        id: data.id,
        name: data.name || data.id,
        path: data.path,
        languages: data.languages,
      }
      if (data.description) body.description = data.description
      return apiFetch<Repository>('/repos', {
        method: 'POST',
        body: JSON.stringify(body),
      })
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repos'] })
      setShowAddForm(false)
      setFormData({ id: '', name: '', path: '', languages: [], description: '' })
    },
  })

  const deleteMutation = useMutation({
    mutationFn: deleteRepo,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repos'] })
      setDeleteConfirm(null)
    },
  })

  const reindexMutation = useMutation({
    mutationFn: reindexRepo,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['repos'] })
    },
  })

  const handleStatusUpdate = (repo: Repository) => {
    setRepoStatuses((prev) => new Map(prev).set(repo.id, repo))
    if (repo.status === 'READY' || repo.status === 'ERROR') {
      queryClient.invalidateQueries({ queryKey: ['repos'] })
    }
  }

  const toggleLang = (l: Language) =>
    setFormData((prev) => ({
      ...prev,
      languages: prev.languages.includes(l)
        ? prev.languages.filter((x) => x !== l)
        : [...prev.languages, l],
    }))

  const allRepos = repos?.map((r) => ({
    ...r,
    ...(repoStatuses.get(r.id) || {}),
  })) ?? []

  const indexingRepos = allRepos.filter((r) => r.status === 'INDEXING')

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
      </div>
    )
  }

  if (isError) {
    return <div className="p-4 text-red-400 text-sm">Failed to load repositories</div>
  }

  return (
    <div className="p-4 space-y-4">
      {/* Polling for indexing repos */}
      {indexingRepos.map((r) => (
        <RepoPolling key={r.id} repoId={r.id} onUpdate={handleStatusUpdate} />
      ))}

      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Database size={18} className="text-slate-400" />
          <h2 className="text-base font-semibold text-slate-200">Repositories</h2>
          <span className="text-xs text-slate-500 bg-slate-700 px-2 py-0.5 rounded-full">
            {allRepos.length}
          </span>
        </div>
        <button
          onClick={() => setShowAddForm((v) => !v)}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 hover:bg-blue-500 text-white rounded text-sm transition-colors"
        >
          <Plus size={14} />
          Add Repo
          {showAddForm ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
        </button>
      </div>

      {/* Add form */}
      {showAddForm && (
        <div className="bg-slate-800 border border-slate-700 rounded-lg p-4 space-y-3">
          <h3 className="text-sm font-medium text-slate-300">Add Repository</h3>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-xs text-slate-500 mb-1 block">ID *</label>
              <input
                value={formData.id}
                onChange={(e) => setFormData((p) => ({ ...p, id: e.target.value }))}
                placeholder="my-repo"
                className="w-full px-2 py-1.5 bg-slate-700 border border-slate-600 rounded text-sm text-slate-300 focus:outline-none focus:border-blue-500"
              />
            </div>
            <div>
              <label className="text-xs text-slate-500 mb-1 block">Name *</label>
              <input
                value={formData.name}
                onChange={(e) => setFormData((p) => ({ ...p, name: e.target.value }))}
                placeholder="My Repository"
                className="w-full px-2 py-1.5 bg-slate-700 border border-slate-600 rounded text-sm text-slate-300 focus:outline-none focus:border-blue-500"
              />
            </div>
          </div>
          <div>
            <label className="text-xs text-slate-500 mb-1 block">Path *</label>
            <div className="flex gap-2">
              <input
                value={formData.path}
                onChange={(e) => setFormData((p) => ({ ...p, path: e.target.value }))}
                placeholder="/path/to/repo"
                className="flex-1 px-2 py-1.5 bg-slate-700 border border-slate-600 rounded text-sm text-slate-300 font-mono focus:outline-none focus:border-blue-500"
              />
              <button
                type="button"
                onClick={() => setShowDirPicker(true)}
                className="flex items-center gap-1.5 px-3 py-1.5 bg-slate-700 hover:bg-slate-600 border border-slate-600 text-slate-400 hover:text-white rounded text-sm transition-colors shrink-0"
                title="Browse directories"
              >
                <FolderOpen size={14} />
                Browse
              </button>
            </div>
          </div>
          <div>
            <label className="text-xs text-slate-500 mb-1 block">Description</label>
            <input
              value={formData.description}
              onChange={(e) => setFormData((p) => ({ ...p, description: e.target.value }))}
              placeholder="Optional description"
              className="w-full px-2 py-1.5 bg-slate-700 border border-slate-600 rounded text-sm text-slate-300 focus:outline-none focus:border-blue-500"
            />
          </div>
          <div>
            <label className="text-xs text-slate-500 mb-1.5 block">Languages</label>
            <div className="flex flex-wrap gap-1.5">
              {ALL_LANGUAGES.map((l) => (
                <button
                  key={l}
                  type="button"
                  onClick={() => toggleLang(l)}
                  className={`px-2 py-0.5 rounded text-xs border transition-colors ${
                    formData.languages.includes(l)
                      ? getLanguageBadgeColor(l)
                      : 'bg-slate-700 text-slate-500 border-slate-600 hover:border-slate-500'
                  }`}
                >
                  {getLanguageLabel(l)}
                </button>
              ))}
            </div>
          </div>

          {showDirPicker && (
            <DirectoryPicker
              currentPath={formData.path}
              onSelect={(selectedPath) => {
                const dirName = selectedPath.split('/').filter(Boolean).pop() || ''
                setFormData((p) => ({
                  ...p,
                  path: selectedPath,
                  id: p.id || dirName.toLowerCase().replace(/[^a-z0-9-]/g, '-'),
                  name: p.name || dirName,
                }))
              }}
              onClose={() => setShowDirPicker(false)}
            />
          )}

          {addMutation.isError && (
            <div className="text-xs text-red-400 bg-red-400/10 rounded p-2">
              {(addMutation.error as Error).message}
            </div>
          )}

          <div className="flex gap-2 justify-end">
            <button
              onClick={() => setShowAddForm(false)}
              className="px-3 py-1.5 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded text-sm transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={() => addMutation.mutate(formData)}
              disabled={!formData.id || !formData.name || !formData.path || addMutation.isPending}
              className="px-3 py-1.5 bg-blue-600 hover:bg-blue-500 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded text-sm transition-colors flex items-center gap-1.5"
            >
              {addMutation.isPending && <Loader size={13} className="animate-spin" />}
              Add & Index
            </button>
          </div>
        </div>
      )}

      {/* Repo list */}
      {allRepos.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-slate-600">
          <Database size={40} className="mb-3 opacity-30" />
          <p className="text-sm">No repositories yet</p>
          <p className="text-xs mt-1">Add a repository to get started</p>
        </div>
      ) : (
        <div className="space-y-3">
          {allRepos.map((repo) => (
            <div key={repo.id} className="bg-slate-800 border border-slate-700 rounded-lg p-4">
              <div className="flex items-start justify-between gap-2 mb-2">
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <h3 className="font-medium text-slate-200 text-sm">{repo.name}</h3>
                    <StatusBadge status={repo.status} />
                  </div>
                  <div className="text-xs font-mono text-slate-500 mt-0.5 break-all">{repo.rootPath}</div>
                </div>
                <div className="flex items-center gap-1.5 shrink-0">
                  <button
                    onClick={() => reindexMutation.mutate(repo.id)}
                    disabled={reindexMutation.isPending}
                    className="flex items-center gap-1 px-2.5 py-1 bg-slate-700 hover:bg-slate-600 text-slate-400 hover:text-white rounded text-xs transition-colors"
                    title="Re-index"
                  >
                    <RefreshCw size={12} className={reindexMutation.isPending ? 'animate-spin' : ''} />
                    Re-index
                  </button>
                  {deleteConfirm === repo.id ? (
                    <div className="flex items-center gap-1">
                      <button
                        onClick={() => deleteMutation.mutate(repo.id)}
                        className="px-2 py-1 bg-red-600 hover:bg-red-500 text-white rounded text-xs transition-colors"
                      >
                        Confirm
                      </button>
                      <button
                        onClick={() => setDeleteConfirm(null)}
                        className="px-2 py-1 bg-slate-700 hover:bg-slate-600 text-slate-300 rounded text-xs transition-colors"
                      >
                        Cancel
                      </button>
                    </div>
                  ) : (
                    <button
                      onClick={() => setDeleteConfirm(repo.id)}
                      className="p-1.5 bg-slate-700 hover:bg-red-600/30 text-slate-500 hover:text-red-400 rounded transition-colors"
                      title="Delete"
                    >
                      <Trash2 size={13} />
                    </button>
                  )}
                </div>
              </div>

              {/* Languages */}
              {repo.languages && repo.languages.length > 0 && (
                <div className="flex flex-wrap gap-1 mb-2">
                  {repo.languages.map((l) => (
                    <span key={l} className={`px-1.5 py-0.5 rounded text-[10px] border ${getLanguageBadgeColor(l)}`}>
                      {getLanguageLabel(l)}
                    </span>
                  ))}
                </div>
              )}

              {/* Stats */}
              <div className="flex gap-4 text-xs text-slate-500">
                <span>{repo.elementCount?.toLocaleString() ?? 0} elements</span>
                <span>{repo.fileCount?.toLocaleString() ?? 0} files</span>
                {repo.lastIndexed && (
                  <span>Indexed {new Date(repo.lastIndexed).toLocaleString()}</span>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
