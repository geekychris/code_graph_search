import type { ElementType, Language } from '../types'

export function getElementTypeColor(type: ElementType): string {
  switch (type) {
    case 'CLASS':
    case 'INTERFACE':
    case 'ENUM':
    case 'STRUCT':
    case 'TRAIT':
      return '#3b82f6' // blue
    case 'METHOD':
    case 'FUNCTION':
    case 'CONSTRUCTOR':
      return '#22c55e' // green
    case 'FIELD':
    case 'PROPERTY':
    case 'ENUM_CONSTANT':
      return '#eab308' // yellow
    case 'FILE':
    case 'DIRECTORY':
    case 'PACKAGE':
    case 'NAMESPACE':
    case 'MODULE':
      return '#a855f7' // purple
    case 'COMMENT_DOC':
    case 'COMMENT_LINE':
    case 'COMMENT_BLOCK':
      return '#94a3b8' // gray
    case 'MARKDOWN_DOCUMENT':
    case 'MARKDOWN_HEADING':
    case 'MARKDOWN_SECTION':
      return '#f97316' // orange
    case 'CONFIG_FILE':
    case 'CONFIG_KEY':
      return '#ef4444' // red
    case 'ANNOTATION':
    case 'ATTRIBUTE':
    case 'DECORATOR':
      return '#06b6d4' // cyan
    case 'PARAMETER':
      return '#8b5cf6' // violet
    case 'REPO':
      return '#f59e0b' // amber
    default:
      return '#64748b' // slate
  }
}

export function getElementTypeBgClass(type: ElementType): string {
  switch (type) {
    case 'CLASS':
    case 'INTERFACE':
    case 'ENUM':
    case 'STRUCT':
    case 'TRAIT':
      return 'bg-blue-500/20 text-blue-300 border-blue-500/30'
    case 'METHOD':
    case 'FUNCTION':
    case 'CONSTRUCTOR':
      return 'bg-green-500/20 text-green-300 border-green-500/30'
    case 'FIELD':
    case 'PROPERTY':
    case 'ENUM_CONSTANT':
      return 'bg-yellow-500/20 text-yellow-300 border-yellow-500/30'
    case 'FILE':
    case 'DIRECTORY':
    case 'PACKAGE':
    case 'NAMESPACE':
    case 'MODULE':
      return 'bg-purple-500/20 text-purple-300 border-purple-500/30'
    case 'COMMENT_DOC':
    case 'COMMENT_LINE':
    case 'COMMENT_BLOCK':
      return 'bg-slate-500/20 text-slate-300 border-slate-500/30'
    case 'MARKDOWN_DOCUMENT':
    case 'MARKDOWN_HEADING':
    case 'MARKDOWN_SECTION':
      return 'bg-orange-500/20 text-orange-300 border-orange-500/30'
    case 'CONFIG_FILE':
    case 'CONFIG_KEY':
      return 'bg-red-500/20 text-red-300 border-red-500/30'
    case 'ANNOTATION':
    case 'ATTRIBUTE':
    case 'DECORATOR':
      return 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
    case 'PARAMETER':
      return 'bg-violet-500/20 text-violet-300 border-violet-500/30'
    default:
      return 'bg-slate-600/20 text-slate-400 border-slate-600/30'
  }
}

export function getElementTypeLabel(type: ElementType): string {
  switch (type) {
    case 'CLASS': return 'Class'
    case 'INTERFACE': return 'Interface'
    case 'ENUM': return 'Enum'
    case 'STRUCT': return 'Struct'
    case 'TRAIT': return 'Trait'
    case 'METHOD': return 'Method'
    case 'FUNCTION': return 'Function'
    case 'CONSTRUCTOR': return 'Constructor'
    case 'FIELD': return 'Field'
    case 'PROPERTY': return 'Property'
    case 'ENUM_CONSTANT': return 'Enum Constant'
    case 'PARAMETER': return 'Parameter'
    case 'FILE': return 'File'
    case 'DIRECTORY': return 'Directory'
    case 'PACKAGE': return 'Package'
    case 'NAMESPACE': return 'Namespace'
    case 'MODULE': return 'Module'
    case 'COMMENT_DOC': return 'Doc Comment'
    case 'COMMENT_LINE': return 'Comment'
    case 'COMMENT_BLOCK': return 'Block Comment'
    case 'ANNOTATION': return 'Annotation'
    case 'ATTRIBUTE': return 'Attribute'
    case 'DECORATOR': return 'Decorator'
    case 'MARKDOWN_DOCUMENT': return 'Markdown'
    case 'MARKDOWN_HEADING': return 'Heading'
    case 'MARKDOWN_SECTION': return 'Section'
    case 'CONFIG_FILE': return 'Config'
    case 'CONFIG_KEY': return 'Config Key'
    case 'REPO': return 'Repository'
    default:
      return type
        .replace(/_/g, ' ')
        .toLowerCase()
        .replace(/\b\w/g, (c) => c.toUpperCase())
  }
}

export function getLanguageBadgeColor(lang: Language): string {
  switch (lang) {
    case 'JAVA': return 'bg-orange-500/20 text-orange-300 border-orange-500/30'
    case 'GO': return 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30'
    case 'RUST': return 'bg-orange-700/20 text-orange-400 border-orange-700/30'
    case 'TYPESCRIPT': return 'bg-blue-500/20 text-blue-300 border-blue-500/30'
    case 'JAVASCRIPT': return 'bg-yellow-500/20 text-yellow-300 border-yellow-500/30'
    case 'C': return 'bg-gray-500/20 text-gray-300 border-gray-500/30'
    case 'CPP': return 'bg-blue-700/20 text-blue-400 border-blue-700/30'
    case 'MARKDOWN': return 'bg-slate-500/20 text-slate-300 border-slate-500/30'
    case 'YAML': return 'bg-green-500/20 text-green-300 border-green-500/30'
    case 'JSON': return 'bg-amber-500/20 text-amber-300 border-amber-500/30'
    default: return 'bg-slate-600/20 text-slate-400 border-slate-600/30'
  }
}

export function getLanguageLabel(lang: Language): string {
  switch (lang) {
    case 'CPP': return 'C++'
    case 'TYPESCRIPT': return 'TypeScript'
    case 'JAVASCRIPT': return 'JavaScript'
    case 'MARKDOWN': return 'Markdown'
    default: return lang.charAt(0) + lang.slice(1).toLowerCase()
  }
}
