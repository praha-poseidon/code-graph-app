import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  AlertCircle, CheckCircle2, CircleHelp, Clock, Download, FileArchive, FolderGit2, KeyRound, Loader2,
  ListTodo, Pencil, Play, Plus, Trash2, UploadCloud, X, XCircle, Copy, Check,
} from 'lucide-react'
import { apiGet, apiPost, request } from '../../lib/http'
import { cn } from '../../lib/utils'

interface Project {
  projectId?: string
  canonicalRepository?: string
  graphScope?: string
  id: number
  name: string
  gitRepoUrl: string
  gitBranch: string
  languages: string[]
  authType: 'NONE' | 'SSH' | 'ACCESS_TOKEN'
  hasAccessToken: boolean
  hasSshPrivateKey: boolean
  endpointRuleCount: number
  status: 'idle' | 'analyzing' | 'done' | 'failed'
  progressCurrent: number
  progressTotal: number
  statusMessage?: string
  lastAnalyzedAt?: string
  latestTaskId?: string
}

const LANGUAGES = [
  ['java', 'Java'], ['go', 'Go'], ['javascript', 'JavaScript'], ['typescript', 'TypeScript'],
  ['python', 'Python'], ['php', 'PHP'], ['kotlin', 'Kotlin'], ['swift', 'Swift'],
] as const

type LanguageToolPackage = {
  downloadUrl: string
}

const LANGUAGE_TOOL_PACKAGES: Record<string, LanguageToolPackage> = {
  java: toolPackage('java'),
  go: toolPackage('go'),
  javascript: toolPackage('javascript'),
  typescript: toolPackage('javascript'),
  python: toolPackage('python'),
  php: toolPackage('php'),
  kotlin: toolPackage('kotlin'),
  swift: toolPackage('swift'),
}

const statusConfig = {
  idle: { label: '待分析', icon: Clock, color: 'text-ink-500 bg-ink-100' },
  analyzing: { label: '分析中', icon: Loader2, color: 'text-brand-600 bg-brand-50' },
  done: { label: '已完成', icon: CheckCircle2, color: 'text-emerald-600 bg-emerald-50' },
  failed: { label: '失败', icon: XCircle, color: 'text-red-600 bg-red-50' },
}

type FormState = {
  gitRepoUrl: string
  gitBranch: string
  language: string
  authType: 'NONE' | 'SSH' | 'ACCESS_TOKEN'
  accessToken: string
  sshPrivateKey: string
  sshPassphrase: string
  endpointRulesArchive: File | null
  clearEndpointRules: boolean
}

const emptyForm: FormState = {
  gitRepoUrl: '',
  gitBranch: '',
  language: 'java',
  authType: 'NONE',
  accessToken: '',
  sshPrivateKey: '',
  sshPassphrase: '',
  endpointRulesArchive: null,
  clearEndpointRules: false,
}

export default function ProjectsPage({ onOpenTasks }: { onOpenTasks?: (repositoryId: number) => void }) {
  const [projects, setProjects] = useState<Project[]>([])
  const [loading, setLoading] = useState(true)
  const [showForm, setShowForm] = useState(false)
  const [editing, setEditing] = useState<Project | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (initial = false) => {
    if (initial) setLoading(true)
    try {
      const response = await apiGet<{ code: number; message: string; data: Project[] }>('/api/config/projects')
      if (response.code !== 200) throw new Error(response.message)
      setProjects(response.data ?? [])
    } catch (cause) {
      if (initial) setError(cause instanceof Error ? cause.message : '仓库配置加载失败')
    } finally {
      if (initial) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const initialTimer = window.setTimeout(() => void load(true), 0)
    const refreshTimer = window.setInterval(() => void load(false), 2500)
    return () => {
      window.clearTimeout(initialTimer)
      window.clearInterval(refreshTimer)
    }
  }, [load])

  const openCreate = () => {
    setEditing(null)
    setForm(emptyForm)
    setError(null)
    setShowForm(true)
  }

  const openEdit = (project: Project) => {
    setEditing(project)
    setForm({
      gitRepoUrl: project.gitRepoUrl,
      gitBranch: project.gitBranch,
      language: project.languages[0] ?? 'java',
      authType: project.authType,
      accessToken: '',
      sshPrivateKey: '',
      sshPassphrase: '',
      endpointRulesArchive: null,
      clearEndpointRules: false,
    })
    setError(null)
    setShowForm(true)
  }

  const handleSave = async () => {
    if (!form.gitRepoUrl.trim()) return setError('仓库地址必填')
    if (!form.language) return setError('请选择源码语言')
    const config = {
      gitRepoUrl: form.gitRepoUrl,
      gitBranch: form.gitBranch,
      languages: [form.language],
      authType: form.authType,
      accessToken: form.accessToken,
      sshPrivateKey: form.sshPrivateKey,
      sshPassphrase: form.sshPassphrase,
      clearEndpointRules: form.clearEndpointRules,
    }
    const body = new FormData()
    body.append('config', new Blob([JSON.stringify(config)], { type: 'application/json' }))
    if (form.endpointRulesArchive) body.append('endpointRules', form.endpointRulesArchive)
    setSaving(true)
    setError(null)
    try {
      const response = editing
        ? await request<{ code: number; message: string }>(`/api/config/projects/${editing.id}`, { method: 'PUT', body })
        : await apiPost<{ code: number; message: string }>('/api/config/projects', body)
      if (response.code !== 200) throw new Error(response.message)
      setShowForm(false)
      await load(false)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存失败')
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (project: Project) => {
    if (!confirm(`确认删除仓库「${project.name}」及其任务记录？`)) return
    await request(`/api/config/projects/${project.id}`, { method: 'DELETE' })
    await load(false)
  }

  const handleAnalyze = async (project: Project) => {
    const response = await apiPost<{ code: number; message: string }>(`/api/config/projects/${project.id}/analyze`)
    if (response.code !== 200) setError(response.message)
    await load(false)
  }

  const summary = useMemo(() => ({
    total: projects.length,
    running: projects.filter(project => project.status === 'analyzing').length,
    failed: projects.filter(project => project.status === 'failed').length,
  }), [projects])
  const selectedTool = LANGUAGE_TOOL_PACKAGES[form.language]

  return (
    <div className="w-full space-y-6 px-6 py-7 animate-fadeIn">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-ink-900">仓库</h2>
          <p className="mt-1 text-sm text-ink-500">仓库、访问凭证、语言和端点规则在这里一次配置完成。</p>
        </div>
        <button onClick={openCreate} className="flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-brand-700">
          <Plus className="h-4 w-4" /> 添加仓库
        </button>
      </div>

      <div className="grid grid-cols-3 gap-3">
        <Metric label="仓库" value={summary.total} />
        <Metric label="正在分析" value={summary.running} accent="text-violet-300" />
        <Metric label="失败" value={summary.failed} accent={summary.failed ? 'text-rose-300' : undefined} />
      </div>

      {error && !showForm && (
        <div className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
          <AlertCircle className="h-4 w-4" /> {error}
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-20"><Loader2 className="h-6 w-6 animate-spin text-ink-400" /></div>
      ) : projects.length === 0 ? (
        <button onClick={openCreate} className="flex w-full flex-col items-center rounded-2xl border border-dashed border-ink-200 py-20 text-center transition hover:border-violet-400/40 hover:bg-white/[0.02]">
          <span className="mb-3 grid h-12 w-12 place-items-center rounded-2xl bg-ink-100"><FolderGit2 className="h-6 w-6 text-ink-400" /></span>
          <span className="text-sm font-medium text-ink-700">添加第一个代码仓库</span>
          <span className="mt-1 text-xs text-ink-400">项目名称会根据仓库地址自动识别</span>
        </button>
      ) : (
        <div className="space-y-3">
          {projects.map(project => <RepositoryCard key={project.id} project={project} onAnalyze={handleAnalyze} onEdit={openEdit} onDelete={handleDelete} onOpenTasks={onOpenTasks} />)}
        </div>
      )}

      {showForm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/55 p-4 backdrop-blur-sm">
          <div className="w-full max-w-2xl overflow-hidden rounded-2xl border border-ink-200 bg-white shadow-2xl">
            <div className="flex items-center justify-between border-b border-ink-100 px-6 py-4">
              <div>
                <h3 className="font-semibold text-ink-900">{editing ? '编辑仓库' : '添加仓库'}</h3>
                <p className="mt-0.5 text-xs text-ink-400">项目名称将从仓库地址自动生成</p>
              </div>
              <button onClick={() => setShowForm(false)} className="text-ink-400 hover:text-ink-600"><X className="h-5 w-5" /></button>
            </div>
            <div className="max-h-[72vh] space-y-5 overflow-y-auto px-6 py-5">
              {error && <div className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-600"><AlertCircle className="h-4 w-4" />{error}</div>}

              <Field label="仓库地址 *">
                <input value={form.gitRepoUrl} onChange={event => setForm(current => ({ ...current, gitRepoUrl: event.target.value }))} placeholder="git@github.com:org/repository.git" className={cn(input(), 'font-mono text-xs')} />
              </Field>
              <Field label="分支">
                <input value={form.gitBranch} onChange={event => setForm(current => ({ ...current, gitBranch: event.target.value }))} placeholder="留空时使用仓库默认分支" className={input()} />
              </Field>

              <Field label="源码语言 *">
                <div className="flex flex-wrap gap-2">
                  {LANGUAGES.map(([value, label]) => (
                    <button key={value} type="button" className={cn(
                      'rounded-lg border px-3 py-2 text-xs font-medium transition',
                      form.language === value ? 'border-violet-400/60 bg-violet-500/15 text-violet-200' : 'border-ink-200 text-ink-500 hover:border-violet-400/30 hover:text-ink-800',
                    )} aria-pressed={form.language === value} onClick={() => setForm(current => ({ ...current, language: value }))}>{label}</button>
                  ))}
                </div>
              </Field>

              <Field label="仓库认证">
                <div className="grid grid-cols-3 gap-2">
                  {([['NONE', '公开仓库'], ['SSH', 'SSH 私钥'], ['ACCESS_TOKEN', 'Access Token']] as const).map(([value, label]) => (
                    <button key={value} type="button" onClick={() => setForm(current => ({ ...current, authType: value }))} className={cn(
                      'flex items-center justify-center gap-2 rounded-lg border px-3 py-2.5 text-xs transition',
                      form.authType === value ? 'border-violet-400/60 bg-violet-500/15 text-violet-200' : 'border-ink-200 text-ink-500',
                    )}><KeyRound className="h-3.5 w-3.5" />{label}</button>
                  ))}
                </div>
              </Field>

              {form.authType === 'ACCESS_TOKEN' && (
                <Field label="Access Token">
                  <input type="password" value={form.accessToken} onChange={event => setForm(current => ({ ...current, accessToken: event.target.value }))} placeholder={editing?.hasAccessToken ? '已保存，留空表示不修改' : '输入仓库访问令牌'} className={input()} />
                </Field>
              )}
              {form.authType === 'SSH' && (
                <div className="space-y-3 rounded-xl border border-ink-200 p-4">
                  <Field label="SSH 私钥">
                    <textarea value={form.sshPrivateKey} onChange={event => setForm(current => ({ ...current, sshPrivateKey: event.target.value }))} rows={6} placeholder={editing?.hasSshPrivateKey ? '已保存，留空表示不修改' : '-----BEGIN OPENSSH PRIVATE KEY-----'} className={cn(input(), 'resize-none font-mono text-xs')} />
                  </Field>
                  <Field label="私钥密码（可选）">
                    <input type="password" value={form.sshPassphrase} onChange={event => setForm(current => ({ ...current, sshPassphrase: event.target.value }))} placeholder="没有密码可留空" className={input()} />
                  </Field>
                </div>
              )}

              <Field label={(
                <span className="flex items-center gap-1.5">
                  <span>端点规则包（可选）</span>
                  <span className="group relative inline-flex">
                    <button
                      type="button"
                      aria-label="什么是端点规则包"
                      aria-describedby="endpoint-rule-help"
                      className="grid h-4 w-4 place-items-center rounded-full text-ink-400 transition hover:text-violet-200 focus:text-violet-200 focus:outline-none focus:ring-2 focus:ring-violet-400/40"
                    >
                      <CircleHelp className="h-3.5 w-3.5" />
                    </button>
                    <span
                      id="endpoint-rule-help"
                      role="tooltip"
                      className="pointer-events-none invisible absolute bottom-full left-0 z-30 mb-2 w-80 max-w-[calc(100vw-3rem)] rounded-xl border border-ink-200 bg-[#171322] p-3 text-left text-xs font-normal leading-5 text-ink-600 opacity-0 shadow-2xl transition group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100"
                    >
                      <strong className="block font-medium text-ink-900">它是做什么的？</strong>
                      <span className="mt-1 block">告诉解析器哪些代码调用属于 HTTP、RPC、数据库或消息队列端点，并生成统一端点标识，用来连接完整调用链。</span>
                      <strong className="mt-2 block font-medium text-ink-900">怎么使用？</strong>
                      <span className="mt-1 block">下载当前语言的工具包，解压并运行 start.sh，按提示选择项目和 Agent。完成后上传桌面生成的 ZIP。</span>
                    </span>
                  </span>
                  {selectedTool && (
                    <a
                      href={selectedTool.downloadUrl}
                      title={`下载 ${LANGUAGES.find(([value]) => value === form.language)?.[1]} 规则生成工具`}
                      aria-label={`下载 ${LANGUAGES.find(([value]) => value === form.language)?.[1]} 规则生成工具`}
                      className="ml-1 inline-flex items-center gap-1 rounded text-xs font-normal text-violet-300 underline-offset-4 transition hover:text-violet-200 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-400/50"
                      download
                    >
                      <Download className="h-3 w-3" /> 下载工具
                    </a>
                  )}
                </span>
              )}>
                <label className="flex cursor-pointer items-center gap-3 rounded-xl border border-dashed border-ink-200 px-4 py-4 transition hover:border-violet-400/50 hover:bg-violet-500/[0.04]">
                  <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-violet-500/10 text-violet-200"><UploadCloud className="h-5 w-5" /></span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium text-ink-700">上传规则包 <span className="ml-1 text-xs font-normal text-ink-400">ZIP</span></span>
                  </span>
                  <input
                    type="file"
                    accept=".zip,application/zip,application/x-zip-compressed"
                    className="hidden"
                    onChange={event => {
                      const archive = event.target.files?.[0] ?? null
                      setForm(current => ({ ...current, endpointRulesArchive: archive, clearEndpointRules: false }))
                      event.target.value = ''
                    }}
                  />
                </label>
                {form.endpointRulesArchive && (
                  <RuleArchiveStatus
                    label={form.endpointRulesArchive.name}
                    detail={`${formatBytes(form.endpointRulesArchive.size)} · 保存后替换当前规则包`}
                    onRemove={() => setForm(current => ({ ...current, endpointRulesArchive: null }))}
                  />
                )}
                {!form.endpointRulesArchive && editing && editing.endpointRuleCount > 0 && !form.clearEndpointRules && (
                  <RuleArchiveStatus
                    label="已上传规则包"
                    detail={`${editing.endpointRuleCount} 个 .ser 规则文件`}
                    onRemove={() => setForm(current => ({ ...current, clearEndpointRules: true }))}
                  />
                )}
                {editing && form.clearEndpointRules && (
                  <div className="flex items-center justify-between rounded-lg border border-rose-400/20 bg-rose-400/[0.05] px-3 py-2 text-xs text-rose-200">
                    <span>保存后将移除现有规则包</span>
                    <button type="button" onClick={() => setForm(current => ({ ...current, clearEndpointRules: false }))} className="text-ink-400 hover:text-white">撤销</button>
                  </div>
                )}
              </Field>
            </div>
            <div className="flex justify-end gap-2 border-t border-ink-100 px-6 py-4">
              <button onClick={() => setShowForm(false)} className="rounded-lg px-4 py-2 text-sm text-ink-600 hover:bg-ink-50">取消</button>
              <button onClick={handleSave} disabled={saving} className="flex items-center gap-2 rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-50">
                {saving && <Loader2 className="h-4 w-4 animate-spin" />}{saving ? '保存中…' : '保存仓库'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

function RepositoryCard({ project, onAnalyze, onEdit, onDelete, onOpenTasks }: {
  project: Project
  onAnalyze: (project: Project) => void
  onEdit: (project: Project) => void
  onDelete: (project: Project) => void
  onOpenTasks?: (repositoryId: number) => void
}) {
  const config = statusConfig[project.status]
  const StatusIcon = config.icon
  const progress = project.progressTotal > 0 ? Math.round(project.progressCurrent / project.progressTotal * 100) : 0
  return (
    <article className="rounded-xl border border-ink-200 bg-white px-5 py-4 shadow-card">
      <div className="flex items-start gap-4">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand-50"><FolderGit2 className="h-5 w-5 text-brand-600" /></span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-semibold text-ink-900">{project.name}</span>
            <span className={cn('flex items-center gap-1 rounded px-2 py-0.5 text-[11px] font-medium', config.color)}>
              <StatusIcon className={cn('h-3 w-3', project.status === 'analyzing' && 'animate-spin')} />{config.label}
            </span>
            {project.languages.map(language => <span key={language} className="rounded bg-white/[0.05] px-2 py-0.5 text-[10px] uppercase text-ink-400">{language}</span>)}
          </div>
          <p className="mt-1 truncate font-mono text-xs text-ink-400">{project.gitRepoUrl}</p>
          {project.projectId && <details className="mt-2 text-[11px] text-ink-400">
            <summary className="cursor-pointer">项目标识</summary>
            <CompactIdentifier value={project.projectId} />
            <p className="mt-1 break-all">{project.canonicalRepository}</p>
            {project.graphScope && <CompactIdentifier value={project.graphScope} />}
          </details>}
          <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-ink-400">
            <span>分支：{project.gitBranch || '默认分支'}</span>
            <span>认证：{project.authType === 'NONE' ? '公开仓库' : project.authType}</span>
            <span>端点规则：{project.endpointRuleCount}</span>
            {project.lastAnalyzedAt && <span>完成时间：{new Date(project.lastAnalyzedAt).toLocaleString()}</span>}
          </div>
          {project.status === 'analyzing' && (
            <div className="mt-3 max-w-xl">
              <div className="mb-1 flex justify-between text-[11px] text-ink-400"><span className="truncate">{project.statusMessage || '等待执行'}</span><span>{project.progressTotal ? `${project.progressCurrent}/${project.progressTotal}` : '准备中'}</span></div>
              <div className="h-1.5 overflow-hidden rounded-full bg-white/[0.06]"><div className="h-full rounded-full bg-violet-500 transition-all" style={{ width: `${Math.max(progress, project.progressTotal ? 2 : 10)}%` }} /></div>
            </div>
          )}
          {project.status === 'failed' && project.statusMessage && <p className="mt-2 text-xs text-red-500">{project.statusMessage}</p>}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {onOpenTasks && <button onClick={() => onOpenTasks(project.id)} title="查看任务" className="grid h-8 w-8 place-items-center rounded-lg text-violet-300 hover:bg-violet-500/10"><ListTodo className="h-4 w-4" /></button>}
          <button onClick={() => onAnalyze(project)} disabled={project.status === 'analyzing'} title="开始分析" className="grid h-8 w-8 place-items-center rounded-lg text-emerald-600 hover:bg-emerald-50 disabled:opacity-40"><Play className="h-4 w-4" /></button>
          <button onClick={() => onEdit(project)} title="编辑" className="grid h-8 w-8 place-items-center rounded-lg text-ink-500 hover:bg-ink-50"><Pencil className="h-4 w-4" /></button>
          <button onClick={() => onDelete(project)} title="删除" className="grid h-8 w-8 place-items-center rounded-lg text-red-500 hover:bg-red-50"><Trash2 className="h-4 w-4" /></button>
        </div>
      </div>
    </article>
  )
}

function CompactIdentifier({ value }: { value: string }) {
  const [copied, setCopied] = useState(false)
  const compact = value.length > 24 ? `${value.slice(0, 10)}…${value.slice(-10)}` : value
  return (
    <button
      type="button"
      title={`${value}（点击复制）`}
      aria-label={`复制完整标识 ${value}`}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value)
          setCopied(true)
          window.setTimeout(() => setCopied(false), 1600)
        } catch {
          setCopied(false)
        }
      }}
      className="mt-1 flex max-w-full items-center gap-1 text-left font-mono text-[11px] text-ink-500 hover:text-brand-600"
    >
      <span className="truncate">{compact}</span>
      {copied ? <Check className="h-3 w-3 shrink-0 text-emerald-600" /> : <Copy className="h-3 w-3 shrink-0" />}
    </button>
  )
}

function Metric({ label, value, accent }: { label: string; value: number; accent?: string }) {
  return <div className="rounded-xl border border-ink-200 bg-white px-4 py-3"><p className="text-xs text-ink-400">{label}</p><p className={cn('mt-1 text-xl font-semibold text-ink-900', accent)}>{value}</p></div>
}

function Field({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
  return <div className="space-y-1.5"><div className="text-xs font-medium text-ink-600">{label}</div>{children}</div>
}

function RuleArchiveStatus({ label, detail, onRemove }: { label: string; detail: string; onRemove: () => void }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-ink-200 bg-white/[0.02] px-3 py-2.5">
      <FileArchive className="h-4 w-4 shrink-0 text-violet-200" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-xs font-medium text-ink-700">{label}</p>
        <p className="mt-0.5 text-[11px] text-ink-400">{detail}</p>
      </div>
      <button type="button" onClick={onRemove} className="grid h-7 w-7 place-items-center rounded-lg text-ink-400 hover:bg-rose-400/10 hover:text-rose-300" title="移除规则包"><X className="h-3.5 w-3.5" /></button>
    </div>
  )
}

function formatBytes(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

function toolPackage(packageLanguage: string): LanguageToolPackage {
  return {
    downloadUrl: `https://github.com/praha-poseidon/code-graph-engine/releases/latest/download/codegraph-tools-${packageLanguage}-linux-x64.tar.gz`,
  }
}

function input() {
  return 'w-full rounded-lg border border-ink-200 bg-white px-3 py-2.5 text-sm text-ink-900 outline-none transition focus:border-brand-400'
}
