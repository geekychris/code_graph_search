// Project stats (docs/STATS.md): files and lines by language and kind.

export interface Counts {
  files: number
  lines: number
  code: number
  comment: number
  blank: number
  bytes: number
}

export interface FileLines { path: string; language: string; kind: string; lines: number; code: number }

/** GET /api/repos/{id}/stats */
export interface RepoStatsView {
  repo: string
  commit: string
  computedAt: string
  totals: Counts
  byLanguage: Record<string, Counts>
  byKind: Record<string, Counts>
  cells: Record<string, Record<string, Counts>>
  byDir: Record<string, Counts>
  largest: FileLines[]
  excluded: number
}

export type Dimension = 'repo' | 'family' | 'language' | 'kind' | 'visibility' | 'tag' | 'none'
export type Metric = 'code' | 'lines' | 'files' | 'comment' | 'blank' | 'bytes'

export interface StatsQuery {
  group: Dimension
  breakdown: Dimension
  metric: Metric
  repo?: string
  family?: string
  language?: string
  kind?: string
  visibility?: string
  tag?: string
  limit?: number
}

/** GET /api/stats */
export interface StatsRollup {
  totals: Counts
  repos: number
  groups: { key: string; counts: Counts; repos: number; breakdown: { key: string; counts: Counts }[] }[]
}
