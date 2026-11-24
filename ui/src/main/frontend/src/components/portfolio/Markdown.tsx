import React, { useMemo } from 'react'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/** Markdown from dossiers and session write-ups, sanitised before it reaches the DOM. */
export default function Markdown({ text, className = '' }: { text?: string | null; className?: string }) {
  const html = useMemo(() => {
    if (!text) return ''
    return DOMPurify.sanitize(marked.parse(text, { async: false, gfm: true, breaks: false }) as string)
  }, [text])
  if (!text) return null
  return <div className={`md ${className}`} dangerouslySetInnerHTML={{ __html: html }} />
}
