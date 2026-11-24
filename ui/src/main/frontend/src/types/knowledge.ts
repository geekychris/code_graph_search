export interface RepoCard {
  repoId: string
  family?: string
  category?: string
  status?: string
  maturity?: number
  purpose?: string
  languages?: string[]
  tech?: string[]
  tags?: string[]
  related?: string[]
  supersededBy?: string
  analyzed?: string
  analyzedCommit?: string
  sections?: Record<string, string>
  source?: string
}

export interface Anchor {
  label?: string
  qualifiedName?: string
  path?: string
  elementId?: string
  elementType?: string
}

export interface Concept {
  slug: string
  name: string
  kind: string
  summary?: string
  details?: string
  howToUse?: string
  anchors?: Anchor[]
  related?: string[]
}

export interface RepoKnowledge {
  repoId: string
  overview?: string
  diagram?: string
  concepts?: Concept[]
  commit?: string
  author?: string
  updated?: string
}

export interface PortfolioRepo {
  id: string
  visibility: 'PUBLIC' | 'PRIVATE'
  status: string
  elements: number
  files: number
  languages?: string[]
  pushedAt?: string
  archived: boolean
  url?: string
  family?: string
  category?: string
  lifecycle?: string
  maturity?: number
  purpose?: string
  tags: string[]
  related: string[]
  described: boolean
  concepts: number
}

export interface PortfolioOverview {
  stats: { repos: number; elements: number; files: number; described: number }
  languages: Record<string, number>
  families: Record<string, string[]>
  repos: PortfolioRepo[]
  edges: { from: string; to: string }[]
}

export interface RepoOverview {
  repo: {
    id: string
    visibility?: string
    elementCount: number
    fileCount: number
    languages?: string[]
    htmlUrl?: string
    defaultBranch?: string
    indexedCommit?: string
    pushedAt?: string
    archived?: boolean
    description?: string
  }
  card?: RepoCard | null
  knowledge?: RepoKnowledge | null
  dependsOn?: string[]
  usedBy?: string[]
}

export interface ArchGroup {
  id: string
  label: string
  files: number
  elements: number
  types: number
  languages: Record<string, number>
  hasChildren: boolean
  inbound: number
  outbound: number
}

export interface ArchEdge { from: string; to: string; weight: number; kinds: Record<string, number> }
export interface ArchExternal { repoId: string; weight: number; fromGroups: Record<string, number>; targets: string[] }
export interface KeyType {
  id: string
  name: string
  qualifiedName: string
  kind: string
  path: string
  group: string
  inbound: number
  crossRepoInbound: number
}

export interface Architecture {
  repoId: string
  path: string
  breadcrumbs: string[]
  groups: ArchGroup[]
  edges: ArchEdge[]
  external: ArchExternal[]
  keyTypes: KeyType[]
  commit: string
}

export interface ConceptUsage { repoId: string; summary?: string; howToUse?: string; anchors?: Anchor[] }

export interface ConceptEntry {
  slug: string
  name: string
  kind: string
  source: 'session' | 'tag' | 'family'
  summary?: string
  repos: ConceptUsage[]
}

export interface WorkItem { repoId: string; reason: string; indexedCommit?: string; describedCommit?: string }

export interface RepoDeps {
  repo: string
  produces: string[]
  dependencies: { fromRepo: string; toRepo: string; coordinate: string; usedVersion?: string; scope?: string }[]
  dependents: { fromRepo: string; toRepo: string; coordinate: string; usedVersion?: string; scope?: string }[]
}
