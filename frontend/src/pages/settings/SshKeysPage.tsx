import { useState, useEffect } from 'react'
import { Plus, Trash2, KeyRound, Loader2, AlertCircle, CheckCircle2, X, Eye, EyeOff } from 'lucide-react'
import { apiGet, apiPost, request } from '../../lib/http'
import { cn } from '../../lib/utils'

interface SshKey {
  id: number
  keyId: string
  description?: string
  createdAt: string
}

const emptyForm = { keyId: '', description: '', privateKey: '', passphrase: '' }

export default function SshKeysPage() {
  const [keys,     setKeys]     = useState<SshKey[]>([])
  const [loading,  setLoading]  = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [form,     setForm]     = useState(emptyForm)
  const [saving,   setSaving]   = useState(false)
  const [error,    setError]    = useState<string | null>(null)
  const [success,  setSuccess]  = useState<string | null>(null)
  const [showKey,  setShowKey]  = useState(false)

  const load = async () => {
    setLoading(true)
    try {
      const res = await apiGet<{ data: SshKey[] }>('/api/config/ssh-keys')
      setKeys(res.data ?? [])
    } catch { /* ignore */ }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const openCreate = () => {
    setForm(emptyForm)
    setError(null)
    setSuccess(null)
    setShowKey(false)
    setShowForm(true)
  }

  const handleSave = async () => {
    if (!form.keyId.trim())      { setError('密钥标识必填'); return }
    if (!form.privateKey.trim()) { setError('私钥内容必填'); return }
    setSaving(true); setError(null)
    try {
      await apiPost('/api/config/ssh-keys', form)
      setShowForm(false)
      setSuccess('密钥保存成功')
      setTimeout(() => setSuccess(null), 3000)
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    } finally { setSaving(false) }
  }

  const handleDelete = async (keyId: string) => {
    if (!confirm(`确认删除密钥「${keyId}」？删除后关联的仓库将无法使用该密钥。`)) return
    await request(`/api/config/ssh-keys/${keyId}`, { method: 'DELETE' })
    load()
  }

  return (
    <div className="max-w-3xl mx-auto px-6 py-6 space-y-5 animate-fadeIn">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-ink-900">SSH 密钥管理</h2>
          <p className="text-sm text-ink-500 mt-0.5">配置 SSH 私钥，用于 Git 仓库认证。私钥加密存储，不会明文展示。</p>
        </div>
        <button
          onClick={openCreate}
          className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 transition-colors"
        >
          <Plus className="w-4 h-4" /> 添加密钥
        </button>
      </div>

      {success && (
        <div className="flex items-center gap-2 text-sm text-emerald-700 bg-emerald-50 border border-emerald-200 rounded-lg px-4 py-2.5">
          <CheckCircle2 className="w-4 h-4 shrink-0" /> {success}
        </div>
      )}

      {/* List */}
      {loading ? (
        <div className="flex justify-center py-16">
          <Loader2 className="w-6 h-6 animate-spin text-ink-400" />
        </div>
      ) : keys.length === 0 ? (
        <div className="flex flex-col items-center py-20 text-center">
          <div className="w-12 h-12 rounded-2xl bg-ink-100 flex items-center justify-center mb-3">
            <KeyRound className="w-6 h-6 text-ink-400" />
          </div>
          <p className="text-sm text-ink-500 mb-1">还没有 SSH 密钥</p>
          <p className="text-xs text-ink-400">添加私钥后，可在仓库配置中选择使用</p>
        </div>
      ) : (
        <div className="bg-white border border-ink-200 rounded-xl shadow-card overflow-hidden">
          <div className="divide-y divide-ink-100">
            {keys.map((k) => (
              <div key={k.keyId} className="flex items-center gap-4 px-5 py-3.5 hover:bg-ink-50 transition-colors">
                <div className="w-8 h-8 rounded-lg bg-amber-50 flex items-center justify-center shrink-0">
                  <KeyRound className="w-4 h-4 text-amber-600" />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-sm text-ink-900">{k.keyId}</span>
                    <span className="text-[11px] px-1.5 py-0.5 rounded bg-ink-100 text-ink-500">已加密存储</span>
                  </div>
                  <div className="text-xs text-ink-400 mt-0.5 flex items-center gap-3">
                    {k.description && <span>{k.description}</span>}
                    <span>添加于 {new Date(k.createdAt).toLocaleDateString()}</span>
                  </div>
                </div>
                <button
                  onClick={() => handleDelete(k.keyId)}
                  title="删除"
                  className="w-8 h-8 rounded-lg flex items-center justify-center text-red-400 hover:bg-red-50 hover:text-red-600 transition-colors shrink-0"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 使用说明 */}
      <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 space-y-2">
        <p className="text-xs font-semibold text-amber-800">使用说明</p>
        <ol className="text-xs text-amber-700 space-y-1 list-decimal list-inside">
          <li>在本地生成 SSH 密钥对：<code className="font-mono bg-amber-100 px-1 rounded">ssh-keygen -t ed25519 -C "your@email.com"</code></li>
          <li>将<strong>公钥</strong>（<code className="font-mono bg-amber-100 px-1 rounded">id_ed25519.pub</code>）添加到 GitLab / GitHub 的 SSH Keys 设置中</li>
          <li>将<strong>私钥</strong>（<code className="font-mono bg-amber-100 px-1 rounded">id_ed25519</code>）内容粘贴到此处</li>
          <li>在仓库管理中选择该密钥即可使用 SSH 认证 Clone 代码</li>
        </ol>
      </div>

      {/* Modal */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden">
            <div className="flex items-center justify-between px-6 py-4 border-b border-ink-100">
              <h3 className="font-semibold text-ink-900">添加 SSH 密钥</h3>
              <button onClick={() => setShowForm(false)} className="text-ink-400 hover:text-ink-600">
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="px-6 py-5 space-y-4">
              {error && (
                <div className="flex items-center gap-2 text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                  <AlertCircle className="w-4 h-4 shrink-0" /> {error}
                </div>
              )}

              <div className="space-y-1.5">
                <label className="text-xs font-medium text-ink-600">密钥标识 *</label>
                <input
                  value={form.keyId}
                  onChange={e => setForm(f => ({ ...f, keyId: e.target.value }))}
                  placeholder="例：cooper-deploy-key"
                  className={inputCls()}
                />
                <p className="text-[11px] text-ink-400">用于在仓库配置中识别该密钥，建议用有含义的名称</p>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-medium text-ink-600">描述</label>
                <input
                  value={form.description}
                  onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                  placeholder="例：Cooper 代码仓库部署密钥"
                  className={inputCls()}
                />
              </div>

              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs font-medium text-ink-600">SSH 私钥内容 *</label>
                  <button
                    type="button"
                    onClick={() => setShowKey(v => !v)}
                    className="flex items-center gap-1 text-[11px] text-ink-400 hover:text-ink-600"
                  >
                    {showKey ? <EyeOff className="w-3 h-3" /> : <Eye className="w-3 h-3" />}
                    {showKey ? '隐藏' : '显示'}
                  </button>
                </div>
                <textarea
                  value={form.privateKey}
                  onChange={e => setForm(f => ({ ...f, privateKey: e.target.value }))}
                  placeholder={'-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----'}
                  rows={6}
                  className={cn(inputCls(), 'font-mono text-xs resize-none', !showKey && 'text-security-disc')}
                  style={!showKey ? { WebkitTextSecurity: 'disc' } as React.CSSProperties : {}}
                />
                <p className="text-[11px] text-ink-400">粘贴完整私钥内容，包含首尾的 BEGIN/END 行</p>
              </div>

              <div className="space-y-1.5">
                <label className="text-xs font-medium text-ink-600">私钥密码（可选）</label>
                <input
                  type="password"
                  value={form.passphrase}
                  onChange={e => setForm(f => ({ ...f, passphrase: e.target.value }))}
                  placeholder="如生成时未设置密码则留空"
                  className={inputCls()}
                />
              </div>
            </div>

            <div className="flex justify-end gap-2 px-6 py-4 border-t border-ink-100">
              <button onClick={() => setShowForm(false)} className="px-4 py-2 rounded-lg text-sm text-ink-600 hover:bg-ink-50 transition-colors">取消</button>
              <button
                onClick={handleSave}
                disabled={saving}
                className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 disabled:opacity-50 transition-colors"
              >
                {saving && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                {saving ? '保存中…' : '保存密钥'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function inputCls() {
  return 'w-full px-3 py-2 text-sm border border-ink-200 rounded-lg bg-white text-ink-900 outline-none focus:border-brand-400 transition-colors'
}
