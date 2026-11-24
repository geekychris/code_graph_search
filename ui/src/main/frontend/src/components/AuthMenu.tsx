import React, { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { KeyRound, LogOut, Lock, Globe, Shield } from 'lucide-react'
import { whoAmI, getToken, setToken } from '../api/client'

/**
 * Who the browser is: anonymous (public repos only) or signed in with an API token
 * (public and private, maybe admin). The token stays in this browser's storage.
 */
export default function AuthMenu() {
  const qc = useQueryClient()
  const me = useQuery({ queryKey: ['whoami'], queryFn: whoAmI, retry: false })
  const [open, setOpen] = useState(false)
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)

  const signIn = async () => {
    setError(null)
    const previous = getToken()
    setToken(value.trim())
    try {
      const who = await whoAmI()
      if (who.name === 'anonymous') throw new Error('not recognised')
      setOpen(false)
      setValue('')
      qc.invalidateQueries()
    } catch {
      setToken(previous)
      setError('That token was not accepted.')
    }
  }

  const signOut = () => {
    setToken(null)
    setOpen(false)
    qc.invalidateQueries()
  }

  const who = me.data
  const label = !who ? '…' : who.name === 'anonymous' ? 'Public view' : who.name
  const icon = who?.admin ? <Shield size={13} className="text-emerald-400" />
    : who?.seesPrivate ? <Lock size={13} className="text-amber-400" /> : <Globe size={13} className="text-slate-400" />

  return (
    <div className="relative ml-auto">
      <button onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg bg-slate-800 border border-slate-700 text-xs text-slate-300 hover:text-white">
        {icon}{label}
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-80 bg-slate-900 border border-slate-700 rounded-lg shadow-2xl p-4 z-50 space-y-3">
          {who && who.name !== 'anonymous' ? (
            <>
              <div className="text-sm text-white">Signed in as {who.name}</div>
              <div className="text-xs text-slate-400">
                {who.seesPrivate ? 'You see public and private repos.' : 'This token sees public repos only.'}
                {who.admin ? ' Admin: you can re-index and write concepts.' : ''}
              </div>
              <button onClick={signOut} className="flex items-center gap-1.5 text-xs text-slate-300 hover:text-white">
                <LogOut size={13} />Sign out
              </button>
            </>
          ) : (
            <>
              <div className="text-sm text-white flex items-center gap-2"><KeyRound size={14} />Sign in with an API token</div>
              <div className="text-xs text-slate-400">
                Without a token you see public repos only. Tokens are in the server's secret (on spark:
                <code className="text-slate-300"> codegraph/codegraph-secrets</code>).
              </div>
              <input type="password" value={value} onChange={(e) => setValue(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && signIn()} placeholder="Token" autoFocus
                className="w-full bg-slate-800 border border-slate-700 rounded px-2 py-1.5 text-sm focus:outline-none focus:border-blue-500" />
              {error && <div className="text-xs text-red-400">{error}</div>}
              <button onClick={signIn} disabled={!value.trim()}
                className="w-full bg-blue-600 hover:bg-blue-500 disabled:opacity-40 text-white text-sm rounded py-1.5">Sign in</button>
            </>
          )}
        </div>
      )}
    </div>
  )
}
