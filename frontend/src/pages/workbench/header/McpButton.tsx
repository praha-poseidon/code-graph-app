import { useEffect, useState } from 'react'
import { Check, Copy } from 'lucide-react'

export default function McpButton() {
  const [endpoint, setEndpoint] = useState('')
  const [copied, setCopied] = useState(false)
  const [manual, setManual] = useState(false)
  const [requiresAuth, setRequiresAuth] = useState(false)
  useEffect(() => {
    const abort = new AbortController()
    fetch('/api/mcp', { signal: abort.signal }).then(response => {
      if (!response.ok) throw new Error('MCP unavailable')
      return response.json()
    }).then(info => {
      if (info.enabled) {
        setEndpoint(new URL(info.endpoint, window.location.origin).href)
        setRequiresAuth(info.authenticationRequired)
      }
    }).catch(() => { /* No active service: leave the control disabled. */ })
    return () => abort.abort()
  }, [])
  useEffect(() => {
    if (!copied) return
    const timer = window.setTimeout(() => setCopied(false), 2000)
    return () => window.clearTimeout(timer)
  }, [copied])
  async function copy() {
    try { await navigator.clipboard.writeText(endpoint); setCopied(true); setManual(false) }
    catch { setManual(true) }
  }
  return <div className="relative">
    <button type="button" onClick={() => void copy()} disabled={!endpoint}
      title={!endpoint ? 'MCP 未启用' : requiresAuth ? '复制 MCP 地址；连接时需配置访问令牌' : '复制 MCP 地址'}
      className="flex h-9 items-center gap-1.5 rounded-lg px-2 text-xs text-[#9d97b6] transition hover:bg-white/[0.06] hover:text-white disabled:opacity-40">
      {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
      {copied ? '已复制' : 'MCP 地址'}
    </button>
    {manual && <input readOnly autoFocus value={endpoint} onFocus={event => event.currentTarget.select()}
      aria-label="MCP 地址，请手动复制" onBlur={() => setManual(false)}
      className="absolute right-0 top-11 w-80 rounded-lg border border-white/20 bg-[#11111d] p-3 text-xs text-white" />}
  </div>
}
