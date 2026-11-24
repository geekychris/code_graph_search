/** Stable colours for families, languages and lifecycle states. */
const FAMILY_COLORS = [
  '#60a5fa', '#f472b6', '#34d399', '#fbbf24', '#a78bfa', '#f87171', '#22d3ee', '#fb923c',
  '#a3e635', '#e879f9', '#2dd4bf', '#facc15', '#818cf8', '#fda4af', '#4ade80', '#c084fc',
]

function hash(s: string) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0
  return Math.abs(h)
}

const known: Record<string, number> = {}
export function familyColor(family?: string | null): string {
  if (!family || family === 'unsorted') return '#64748b'
  if (known[family] === undefined) known[family] = hash(family) % FAMILY_COLORS.length
  return FAMILY_COLORS[known[family]]
}

/**
 * Gives each family its own colour, in a fixed order. The app seeds this once from the
 * portfolio overview so every page agrees; unseeded families fall back to a hash.
 */
export function seedFamilies(families: string[]) {
  families.filter((f) => f !== 'unsorted').sort().forEach((f, i) => { known[f] = i % FAMILY_COLORS.length })
}

export const LANGUAGE_COLORS: Record<string, string> = {
  java: '#f97316', go: '#22d3ee', rust: '#fb7185', typescript: '#3b82f6', javascript: '#facc15',
  c: '#94a3b8', cpp: '#a78bfa', python: '#4ade80', markdown: '#64748b', yaml: '#64748b', json: '#64748b',
}

export function languageColor(lang?: string) {
  return LANGUAGE_COLORS[(lang || '').toLowerCase()] || '#64748b'
}

export const LIFECYCLE: Record<string, { color: string; label: string }> = {
  active: { color: '#22c55e', label: 'active' },
  maintained: { color: '#22c55e', label: 'maintained' },
  experiment: { color: '#a78bfa', label: 'experiment' },
  dormant: { color: '#f59e0b', label: 'dormant' },
  abandoned: { color: '#ef4444', label: 'abandoned' },
  archived: { color: '#64748b', label: 'archived' },
  superseded: { color: '#64748b', label: 'superseded' },
}

export function lifecycleColor(s?: string) {
  return (s && LIFECYCLE[s.toLowerCase()]?.color) || '#64748b'
}

export const KIND_COLORS: Record<string, string> = {
  capability: '#60a5fa', technique: '#f472b6', pattern: '#a78bfa', api: '#34d399',
  infrastructure: '#fbbf24', domain: '#22d3ee', tag: '#94a3b8', family: '#e879f9',
}

export function kindColor(kind?: string) {
  return KIND_COLORS[(kind || '').toLowerCase()] || '#94a3b8'
}

export function formatCount(n: number) {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 10_000) return Math.round(n / 1000) + 'k'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}
