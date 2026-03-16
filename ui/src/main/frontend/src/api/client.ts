import type {
  Element,
  Edge,
  EdgeDirection,
  Repository,
  SearchParams,
  SearchResult,
  SnippetResult,
  PathResult,
  SimilarityResult,
} from '../types'

const BASE = '/api'

export async function apiFetch<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    ...options,
  })
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText)
    throw new Error(`API error ${res.status}: ${text}`)
  }
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

function toQueryString(params: Record<string, unknown>): string {
  const parts: string[] = []
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    if (Array.isArray(v)) {
      for (const item of v) {
        if (item !== undefined && item !== null && item !== '')
          parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(item))}`)
      }
    } else {
      parts.push(`${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    }
  }
  return parts.length ? `?${parts.join('&')}` : ''
}

export function searchCode(params: SearchParams): Promise<SearchResult> {
  return apiFetch<SearchResult>(`/search${toQueryString(params as Record<string, unknown>)}`)
}

export function getElement(id: string): Promise<Element> {
  return apiFetch<Element>(`/elements/${encodeURIComponent(id)}`)
}

export function getSnippet(id: string, context = 5): Promise<SnippetResult> {
  return apiFetch<SnippetResult>(`/elements/${encodeURIComponent(id)}/snippet?context=${context}`)
}

export function getParent(id: string): Promise<Element | null> {
  return apiFetch<Element | null>(`/elements/${encodeURIComponent(id)}/parent`).catch(() => null)
}

export function getAncestors(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/ancestors`)
}

export function getChildren(id: string, type?: string): Promise<Element[]> {
  const qs = type ? `?type=${encodeURIComponent(type)}` : ''
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/children${qs}`)
}

export function getSiblings(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/siblings`)
}

export function getCallers(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/callers`)
}

export function getCallees(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/callees`)
}

export function getCallChain(fromId: string, toId: string, depth = 5): Promise<Element[][]> {
  return apiFetch<Element[][]>(
    `/elements/${encodeURIComponent(fromId)}/call-chain?to=${encodeURIComponent(toId)}&depth=${depth}`
  )
}

export function getComments(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/comments`)
}

export function getAnnotations(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/annotations`)
}

export function getUsages(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/usages`)
}

export function getSuperclass(id: string): Promise<Element | null> {
  return apiFetch<Element | null>(`/elements/${encodeURIComponent(id)}/superclass`).catch(() => null)
}

export function getInterfaces(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/interfaces`)
}

export function getSubclasses(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/subclasses`)
}

export function getImplementors(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/implementors`)
}

export function getOverrides(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/overrides`)
}

export function getOverriders(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/overriders`)
}

export function getParameters(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/parameters`)
}

export function getFields(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/fields`)
}

export function getMethods(id: string): Promise<Element[]> {
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/methods`)
}

export function getRelated(id: string, edgeType?: string): Promise<Element[]> {
  const qs = edgeType ? `?edgeType=${encodeURIComponent(edgeType)}` : ''
  return apiFetch<Element[]>(`/elements/${encodeURIComponent(id)}/related${qs}`)
}

export function getSubGraph(
  id: string,
  depth = 2,
  edgeTypes?: string[]
): Promise<{ nodes: Element[]; edges: Edge[] }> {
  const params: Record<string, unknown> = { depth }
  if (edgeTypes && edgeTypes.length) params.edge_types = edgeTypes.join(',')
  return apiFetch<{ nodes: Element[]; edges: Edge[] }>(
    `/elements/${encodeURIComponent(id)}/graph${toQueryString(params)}`
  )
}

export function listRepos(): Promise<Repository[]> {
  return apiFetch<Repository[]>('/repos')
}

export function listRepoFiles(repoId: string): Promise<string[]> {
  return apiFetch<string[]>(`/repos/${encodeURIComponent(repoId)}/files`)
}

export function addRepo(repo: Partial<Repository>): Promise<Repository> {
  return apiFetch<Repository>('/repos', {
    method: 'POST',
    body: JSON.stringify(repo),
  })
}

export function deleteRepo(id: string): Promise<void> {
  return apiFetch<void>(`/repos/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export function reindexRepo(id: string): Promise<void> {
  return apiFetch<void>(`/repos/${encodeURIComponent(id)}/reindex`, { method: 'POST' })
}

export function getRepoStatus(id: string): Promise<Repository> {
  return apiFetch<Repository>(`/repos/${encodeURIComponent(id)}/status`)
}

// ---- FOAF / Connectivity ----

export function findShortestPath(params: {
  from: string; to: string; depth?: number; direction?: EdgeDirection; edges?: string; maxPaths?: number
}): Promise<PathResult> {
  return apiFetch<PathResult>(`/graph/shortest-path${toQueryString(params as Record<string, unknown>)}`)
}

export function findAllShortestPaths(params: {
  from: string; to: string; depth?: number; direction?: EdgeDirection; edges?: string; maxPaths?: number
}): Promise<PathResult> {
  return apiFetch<PathResult>(`/graph/all-shortest-paths${toQueryString(params as Record<string, unknown>)}`)
}

export function findAllPaths(params: {
  from: string; to: string; depth?: number; direction?: EdgeDirection; edges?: string; maxPaths?: number
}): Promise<PathResult> {
  return apiFetch<PathResult>(`/graph/all-paths${toQueryString(params as Record<string, unknown>)}`)
}

export function getSimilarity(params: {
  a: string; b: string; direction?: EdgeDirection; edges?: string
}): Promise<SimilarityResult> {
  return apiFetch<SimilarityResult>(`/graph/similarity${toQueryString(params as Record<string, unknown>)}`)
}

export function getStatus(): Promise<unknown> {
  return apiFetch<unknown>('/status')
}
