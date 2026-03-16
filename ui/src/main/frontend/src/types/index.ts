export type ElementType =
  | 'REPO'
  | 'DIRECTORY'
  | 'FILE'
  | 'PACKAGE'
  | 'NAMESPACE'
  | 'MODULE'
  | 'CLASS'
  | 'INTERFACE'
  | 'ENUM'
  | 'STRUCT'
  | 'TRAIT'
  | 'CONSTRUCTOR'
  | 'METHOD'
  | 'FUNCTION'
  | 'FIELD'
  | 'PROPERTY'
  | 'ENUM_CONSTANT'
  | 'PARAMETER'
  | 'COMMENT_DOC'
  | 'COMMENT_LINE'
  | 'COMMENT_BLOCK'
  | 'ANNOTATION'
  | 'ATTRIBUTE'
  | 'DECORATOR'
  | 'MARKDOWN_DOCUMENT'
  | 'MARKDOWN_HEADING'
  | 'MARKDOWN_SECTION'
  | 'CONFIG_FILE'
  | 'CONFIG_KEY'
  | string

export type Language =
  | 'JAVA'
  | 'GO'
  | 'RUST'
  | 'TYPESCRIPT'
  | 'JAVASCRIPT'
  | 'C'
  | 'CPP'
  | 'MARKDOWN'
  | 'YAML'
  | 'JSON'
  | 'UNKNOWN'

export type EdgeType =
  | 'CONTAINS'
  | 'EXTENDS'
  | 'IMPLEMENTS'
  | 'OVERRIDES'
  | 'CALLS'
  | 'INSTANTIATES'
  | 'USES_TYPE'
  | 'IMPORTS'
  | 'DEPENDS_ON'
  | 'DOCUMENTS'
  | 'ANNOTATES'
  | string

export interface Element {
  id: string
  repoId: string
  elementType: ElementType
  language: Language
  name: string
  qualifiedName: string
  signature?: string
  filePath: string
  lineStart: number
  lineEnd: number
  colStart?: number
  colEnd?: number
  snippet?: string
  docComment?: string
  returnType?: string
  parameterTypes?: string[]
  visibility?: string
  modifiers?: string[]
  parentId?: string
  metadata?: Record<string, string>
}

export interface Edge {
  id: string
  fromId: string
  toId: string
  edgeType: EdgeType
  metadata?: Record<string, string>
}

export interface Repository {
  id: string
  name: string
  rootPath: string
  languages: Language[]
  status: 'PENDING' | 'INDEXING' | 'READY' | 'ERROR' | 'WATCHING'
  lastIndexed?: string
  elementCount: number
  fileCount: number
}

export interface SearchQuery {
  q?: string
  type?: string
  repo?: string
  lang?: string
  file?: string
  sort?: string
  limit?: number
  offset?: number
}

export interface SearchResult {
  total: number
  items: Element[]
  query: SearchQuery
}

export interface SnippetResult {
  element: Element
  snippet: string
  filePath: string
  lineStart: number
  lineEnd: number
}

export type EdgeDirection = 'BOTH' | 'OUTGOING' | 'INCOMING'

export interface PathResult {
  paths: Element[][]
  pathEdges: Edge[][]
  shortestPathLength: number
  computeTimeMs: number
}

export interface SimilarityResult {
  commonNeighbors: Element[]
  neighborCountA: number
  neighborCountB: number
  jaccardSimilarity: number
  adamicAdarIndex: number
  elementA: Element
  elementB: Element
  computeTimeMs: number
}

export interface SearchParams {
  q?: string
  type?: string | string[]
  repo?: string | string[]
  lang?: string | string[]
  file?: string
  sort?: string
  sortDir?: 'asc' | 'desc'
  limit?: number
  offset?: number
}
