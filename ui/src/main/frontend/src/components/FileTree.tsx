import React, { useState, useCallback } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  ChevronRight, ChevronDown, Folder, FolderOpen, File,
  FileCode, FileText, Settings, Search,
} from 'lucide-react'
import { listRepos, listRepoFiles } from '../api/client'

interface Props {
  onFileSelect: (filePath: string) => void
}

interface TreeNode {
  name: string
  path: string
  isDir: boolean
  children?: TreeNode[]
  isLoaded?: boolean
}

function buildTree(paths: string[], prefix: string): TreeNode[] {
  const dirs = new Map<string, string[]>()
  const files: TreeNode[] = []

  for (const path of paths) {
    const relative = prefix ? path.slice(prefix.length).replace(/^\//, '') : path
    const slashIdx = relative.indexOf('/')
    if (slashIdx === -1) {
      // file
      files.push({ name: relative, path, isDir: false })
    } else {
      // directory
      const dirName = relative.slice(0, slashIdx)
      const fullDirPath = prefix ? `${prefix}/${dirName}` : dirName
      if (!dirs.has(fullDirPath)) dirs.set(fullDirPath, [])
      dirs.get(fullDirPath)!.push(path)
    }
  }

  const dirNodes: TreeNode[] = Array.from(dirs.entries()).map(([dirPath, children]) => ({
    name: dirPath.split('/').pop() || dirPath,
    path: dirPath,
    isDir: true,
    children: buildTree(children, dirPath),
    isLoaded: true,
  }))

  return [...dirNodes.sort((a, b) => a.name.localeCompare(b.name)),
    ...files.sort((a, b) => a.name.localeCompare(b.name))]
}

function getFileIcon(name: string) {
  const ext = name.split('.').pop()?.toLowerCase() || ''
  if (['java', 'go', 'rs', 'ts', 'tsx', 'js', 'jsx', 'c', 'cpp', 'h'].includes(ext))
    return <FileCode size={13} className="text-blue-400 shrink-0" />
  if (['md', 'txt'].includes(ext))
    return <FileText size={13} className="text-slate-400 shrink-0" />
  if (['json', 'yaml', 'yml', 'toml', 'xml', 'properties'].includes(ext))
    return <Settings size={13} className="text-yellow-400 shrink-0" />
  return <File size={13} className="text-slate-500 shrink-0" />
}

function TreeNodeView({
  node,
  depth,
  onFileSelect,
}: {
  node: TreeNode
  depth: number
  onFileSelect: (path: string) => void
}) {
  const [expanded, setExpanded] = useState(depth === 0)

  const toggle = useCallback(() => setExpanded((v) => !v), [])

  return (
    <div>
      <button
        onClick={node.isDir ? toggle : () => onFileSelect(node.path)}
        className="w-full flex items-center gap-1 py-0.5 px-1 hover:bg-slate-700/50 rounded text-left transition-colors"
        style={{ paddingLeft: `${depth * 12 + 4}px` }}
      >
        {node.isDir ? (
          <>
            {expanded
              ? <ChevronDown size={13} className="text-slate-500 shrink-0" />
              : <ChevronRight size={13} className="text-slate-500 shrink-0" />}
            {expanded
              ? <FolderOpen size={13} className="text-amber-400 shrink-0" />
              : <Folder size={13} className="text-amber-500 shrink-0" />}
          </>
        ) : (
          <>
            <span className="w-[13px] shrink-0" />
            {getFileIcon(node.name)}
          </>
        )}
        <span className="text-xs text-slate-300 truncate">{node.name}</span>
      </button>
      {node.isDir && expanded && node.children && (
        <div>
          {node.children.map((child) => (
            <TreeNodeView
              key={child.path}
              node={child}
              depth={depth + 1}
              onFileSelect={onFileSelect}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function RepoTree({ repoId, repoName, onFileSelect }: {
  repoId: string
  repoName: string
  onFileSelect: (path: string) => void
}) {
  const [expanded, setExpanded] = useState(false)

  const { data: filePaths, isLoading } = useQuery({
    queryKey: ['filetree', repoId],
    queryFn: () => listRepoFiles(repoId),
    enabled: expanded,
    staleTime: 60_000,
  })

  const treeNodes = expanded ? buildTree(filePaths || [], '') : []

  return (
    <div>
      <button
        onClick={() => setExpanded((v) => !v)}
        className="w-full flex items-center gap-1.5 py-1.5 px-2 hover:bg-slate-700/50 rounded text-left transition-colors"
      >
        {expanded ? <ChevronDown size={13} className="text-slate-500" /> : <ChevronRight size={13} className="text-slate-500" />}
        <Folder size={13} className="text-amber-400 shrink-0" />
        <span className="text-xs font-medium text-slate-300 truncate">{repoName}</span>
        {isLoading && expanded && (
          <div className="ml-auto w-3 h-3 border border-slate-500 border-t-transparent rounded-full animate-spin shrink-0" />
        )}
      </button>
      {expanded && (
        <div>
          {treeNodes.map((node) => (
            <TreeNodeView
              key={node.path}
              node={node}
              depth={1}
              onFileSelect={onFileSelect}
            />
          ))}
          {!isLoading && treeNodes.length === 0 && (
            <div className="text-xs text-slate-600 py-1 pl-6">No files found</div>
          )}
        </div>
      )}
    </div>
  )
}

export default function FileTree({ onFileSelect }: Props) {
  const [filter, setFilter] = useState('')

  const { data: repos, isLoading } = useQuery({
    queryKey: ['repos'],
    queryFn: listRepos,
  })

  return (
    <div className="flex flex-col h-full">
      {/* Filter input */}
      <div className="p-2 border-b border-slate-700">
        <div className="relative">
          <Search size={12} className="absolute left-2 top-1/2 -translate-y-1/2 text-slate-500" />
          <input
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder="Filter files..."
            className="w-full pl-7 pr-2 py-1.5 bg-slate-800 border border-slate-700 rounded text-xs text-slate-300 placeholder-slate-600 focus:outline-none focus:border-blue-500"
          />
        </div>
      </div>

      {/* Tree */}
      <div className="flex-1 overflow-y-auto p-1">
        {isLoading && (
          <div className="flex items-center justify-center py-8">
            <div className="w-4 h-4 border border-blue-500 border-t-transparent rounded-full animate-spin" />
          </div>
        )}
        {repos && repos.length === 0 && (
          <div className="text-xs text-slate-600 text-center py-8">No repositories</div>
        )}
        {repos?.map((repo) => (
          <RepoTree
            key={repo.id}
            repoId={repo.id}
            repoName={repo.name}
            onFileSelect={onFileSelect}
          />
        ))}
      </div>
    </div>
  )
}
