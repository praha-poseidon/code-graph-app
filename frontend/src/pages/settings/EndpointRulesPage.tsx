import { useState, useEffect } from 'react'
import { Plus, Trash2, FileCode2, Loader2, AlertCircle, X, ChevronDown, ChevronUp, Shield } from 'lucide-react'
import { apiGet, apiPost, request } from '../../lib/http'
import { cn } from '../../lib/utils'

interface EndpointRule {
  id: number
  ruleId: string
  name: string
  description?: string
  content: string
  isBuiltin: boolean
  enabled: boolean
  createdAt: string
}

const emptyForm = { ruleId: '', name: '', description: '', content: '' }

export default function EndpointRulesPage() {
  const [rules,    setRules]    = useState<EndpointRule[]>([])
  const [loading,  setLoading]  = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [form,     setForm]     = useState(emptyForm)
  const [saving,   setSaving]   = useState(false)
  const [error,    setError]    = useState<string | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const res = await apiGet<{ data: EndpointRule[] }>('/api/config/endpoint-rules')
      setRules(res.data ?? [])
    } catch { /* ignore */ }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [])

  const handleToggle = async (rule: EndpointRule) => {
    await request(`/api/config/endpoint-rules/${rule.ruleId}/toggle?enabled=${!rule.enabled}`, { method: 'PATCH' })
    load()
  }

  const handleDelete = async (ruleId: string) => {
    if (!confirm(`确认删除规则「${ruleId}」？`)) return
    try {
      await request(`/api/config/endpoint-rules/${ruleId}`, { method: 'DELETE' })
      load()
    } catch (e) {
      alert(e instanceof Error ? e.message : '删除失败，内置规则不允许删除')
    }
  }

  const handleSave = async () => {
    if (!form.ruleId.trim() || !form.name.trim() || !form.content.trim()) {
      setError('规则标识、名称和内容必填'); return
    }
    setSaving(true); setError(null)
    try {
      await apiPost('/api/config/endpoint-rules', form)
      setShowForm(false)
      load()
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败')
    } finally { setSaving(false) }
  }

  const builtins = rules.filter(r => r.isBuiltin)
  const customs  = rules.filter(r => !r.isBuiltin)

  return (
    <div className="max-w-4xl mx-auto px-6 py-6 space-y-6 animate-fadeIn">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-base font-semibold text-ink-900">端点扫描规则</h2>
          <p className="text-sm text-ink-500 mt-0.5">管理 EPR 规则文件，配置如何识别代码中的 HTTP 入口/出口端点</p>
        </div>
        <button
          onClick={() => { setForm(emptyForm); setError(null); setShowForm(true) }}
          className="flex items-center gap-1.5 px-3 py-2 rounded-lg bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 transition-colors"
        >
          <Plus className="w-4 h-4" /> 添加规则
        </button>
      </div>

      {loading ? (
        <div className="flex justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-ink-400" /></div>
      ) : (
        <div className="space-y-5">
          {/* 内置规则 */}
          <section className="space-y-2">
            <div className="flex items-center gap-2">
              <Shield className="w-3.5 h-3.5 text-ink-400" />
              <span className="text-xs font-semibold text-ink-500 uppercase tracking-wider">内置规则</span>
            </div>
            {builtins.map(rule => <RuleCard key={rule.ruleId} rule={rule} expanded={expanded} onToggleExpand={setExpanded} onToggleEnabled={handleToggle} onDelete={handleDelete} />)}
          </section>

          {/* 自定义规则 */}
          {customs.length > 0 && (
            <section className="space-y-2">
              <div className="flex items-center gap-2">
                <FileCode2 className="w-3.5 h-3.5 text-ink-400" />
                <span className="text-xs font-semibold text-ink-500 uppercase tracking-wider">自定义规则</span>
              </div>
              {customs.map(rule => <RuleCard key={rule.ruleId} rule={rule} expanded={expanded} onToggleExpand={setExpanded} onToggleEnabled={handleToggle} onDelete={handleDelete} />)}
            </section>
          )}

          {rules.length === 0 && (
            <div className="flex flex-col items-center py-16 text-center">
              <FileCode2 className="w-10 h-10 text-ink-300 mb-3" />
              <p className="text-sm text-ink-500">暂无规则</p>
            </div>
          )}
        </div>
      )}

      {/* 说明 */}
      <div className="bg-brand-50 border border-brand-100 rounded-xl p-4 space-y-2">
        <p className="text-xs font-semibold text-brand-800">EPR 文件格式说明</p>
        <p className="text-xs text-brand-700 leading-relaxed">
          EPR（Endpoint Pattern Rule）是 YAML 格式的规则文件，定义如何从代码 AST 中提取 HTTP 端点信息。
          内置规则覆盖 Spring MVC 和 WebClient，自定义规则可用于 Feign、RestTemplate、gRPC 等其他框架。
        </p>
        <p className="text-xs text-brand-600">
          在仓库管理中，每个仓库可关联一个或多个规则，分析时按优先级顺序依次应用。
        </p>
      </div>

      {/* 添加表单弹窗 */}
      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl mx-4 overflow-hidden">
            <div className="flex items-center justify-between px-6 py-4 border-b border-ink-100">
              <h3 className="font-semibold text-ink-900">添加端点规则</h3>
              <button onClick={() => setShowForm(false)} className="text-ink-400 hover:text-ink-600"><X className="w-5 h-5" /></button>
            </div>
            <div className="px-6 py-5 space-y-4 max-h-[75vh] overflow-y-auto">
              {error && (
                <div className="flex items-center gap-2 text-sm text-red-600 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                  <AlertCircle className="w-4 h-4 shrink-0" /> {error}
                </div>
              )}
              <div className="grid grid-cols-2 gap-3">
                <Field label="规则标识 *">
                  <input value={form.ruleId} onChange={e => setForm(f => ({ ...f, ruleId: e.target.value }))}
                    placeholder="custom-feign-outbound" className={inputCls()} />
                </Field>
                <Field label="规则名称 *">
                  <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
                    placeholder="Feign Client Outbound" className={inputCls()} />
                </Field>
              </div>
              <Field label="描述">
                <input value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                  placeholder="解析 Feign Client 的出口调用" className={inputCls()} />
              </Field>
              <Field label="EPR 内容（YAML 格式）*">
                <textarea value={form.content} onChange={e => setForm(f => ({ ...f, content: e.target.value }))}
                  rows={14} placeholder={PLACEHOLDER} className={cn(inputCls(), 'font-mono text-xs resize-none')} />
              </Field>
            </div>
            <div className="flex justify-end gap-2 px-6 py-4 border-t border-ink-100">
              <button onClick={() => setShowForm(false)} className="px-4 py-2 rounded-lg text-sm text-ink-600 hover:bg-ink-50 transition-colors">取消</button>
              <button onClick={handleSave} disabled={saving}
                className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand-600 text-white text-sm font-medium hover:bg-brand-700 disabled:opacity-50 transition-colors">
                {saving && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                {saving ? '保存中…' : '保存规则'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function RuleCard({ rule, expanded, onToggleExpand, onToggleEnabled, onDelete }: {
  rule: EndpointRule
  expanded: string | null
  onToggleExpand: (id: string | null) => void
  onToggleEnabled: (rule: EndpointRule) => void
  onDelete: (ruleId: string) => void
}) {
  const isExpanded = expanded === rule.ruleId
  return (
    <div className={cn('bg-white border rounded-xl shadow-card overflow-hidden transition-colors', rule.enabled ? 'border-ink-200' : 'border-ink-100 opacity-60')}>
      <div className="flex items-center gap-3 px-4 py-3">
        <div className={cn('w-8 h-8 rounded-lg flex items-center justify-center shrink-0', rule.isBuiltin ? 'bg-brand-50' : 'bg-violet-50')}>
          <FileCode2 className={cn('w-4 h-4', rule.isBuiltin ? 'text-brand-600' : 'text-violet-600')} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="font-medium text-sm text-ink-900">{rule.name}</span>
            {rule.isBuiltin && <span className="text-[10px] px-1.5 py-0.5 rounded bg-brand-50 text-brand-600 font-medium">内置</span>}
            <span className={cn('text-[10px] px-1.5 py-0.5 rounded font-medium', rule.enabled ? 'bg-emerald-50 text-emerald-600' : 'bg-ink-100 text-ink-400')}>
              {rule.enabled ? '已启用' : '已禁用'}
            </span>
          </div>
          <p className="text-xs text-ink-400 mt-0.5 font-mono">{rule.ruleId}</p>
          {rule.description && <p className="text-xs text-ink-500 mt-0.5">{rule.description}</p>}
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={() => onToggleExpand(isExpanded ? null : rule.ruleId)}
            className="w-7 h-7 rounded-lg flex items-center justify-center text-ink-400 hover:bg-ink-50 transition-colors" title="查看内容">
            {isExpanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          </button>
          <button onClick={() => onToggleEnabled(rule)}
            className="px-2.5 py-1 rounded-lg text-xs text-ink-500 hover:bg-ink-50 transition-colors border border-ink-200">
            {rule.enabled ? '禁用' : '启用'}
          </button>
          {!rule.isBuiltin && (
            <button onClick={() => onDelete(rule.ruleId)}
              className="w-7 h-7 rounded-lg flex items-center justify-center text-red-400 hover:bg-red-50 transition-colors">
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>
      {isExpanded && (
        <div className="border-t border-ink-100 bg-ink-900 px-4 py-3 max-h-64 overflow-y-auto">
          <pre className="text-xs text-ink-100 font-mono whitespace-pre-wrap">{rule.content}</pre>
        </div>
      )}
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <label className="text-xs font-medium text-ink-600">{label}</label>
      {children}
    </div>
  )
}

function inputCls() {
  return 'w-full px-3 py-2 text-sm border border-ink-200 rounded-lg bg-white text-ink-900 outline-none focus:border-brand-400 transition-colors'
}

const PLACEHOLDER = `name: "Custom Rule"
description: "自定义端点扫描规则"
version: "1.0"
enabled: true
priority: 90
type: "http-outbound"

scope:
  packageIncludes:
    - "**.client"
    - "**.feign"

locate:
  nodeType: "MethodDeclaration"
  where:
    - hasAnnotation:
        nameMatches: "@FeignClient"

extract:
  httpMethod:
    strategies:
      - tryConfig:
          from: "method.annotation[.*Mapping$].name"
          defaultValue: "GET"
  path:
    strategies:
      - tryConfig:
          from: "method.annotation[.*Mapping$].attribute[value]"
`
