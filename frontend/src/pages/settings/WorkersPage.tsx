import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  AlertCircle, CircleOff, Cpu, HardDrive, Loader2, RefreshCw,
  Server, ServerCog, TimerReset,
} from 'lucide-react'
import { fetchWorkers, type AnalysisWorker, type AnalysisWorkerStatus } from '../../api/analysisApi'
import { cn } from '../../lib/utils'

const statusMeta: Record<AnalysisWorkerStatus, { label: string; color: string; dot: string }> = {
  IDLE: { label: '空闲', color: 'border-emerald-400/20 bg-emerald-400/10 text-emerald-300', dot: 'bg-emerald-400' },
  WORKING: { label: '工作中', color: 'border-violet-400/25 bg-violet-500/15 text-violet-200', dot: 'bg-violet-400' },
  OFFLINE: { label: '离线', color: 'border-white/10 bg-white/[0.04] text-[#8f88a8]', dot: 'bg-[#5f5972]' },
}

export default function WorkersPage() {
  const [workers, setWorkers] = useState<AnalysisWorker[]>([])
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (initial = false) => {
    if (initial) setLoading(true)
    else setRefreshing(true)
    try {
      setWorkers(await fetchWorkers())
      setError(null)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Worker 状态加载失败')
    } finally {
      setLoading(false)
      setRefreshing(false)
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

  const summary = useMemo(() => ({
    online: workers.filter(worker => worker.status !== 'OFFLINE').length,
    working: workers.filter(worker => worker.status === 'WORKING').length,
    idle: workers.filter(worker => worker.status === 'IDLE').length,
    offline: workers.filter(worker => worker.status === 'OFFLINE').length,
  }), [workers])

  return (
    <div className="w-full space-y-6 px-6 py-7 animate-fadeIn">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-ink-900">Worker 监控</h2>
          <p className="mt-1 text-sm text-ink-500">监控多机器解析节点的存活状态、负载和当前任务。</p>
        </div>
        <button onClick={() => void load(false)} disabled={refreshing} className="flex h-9 items-center gap-2 rounded-lg border border-ink-200 bg-white px-3 text-xs text-ink-500 transition hover:text-ink-800 disabled:opacity-50">
          <RefreshCw className={cn('h-3.5 w-3.5', refreshing && 'animate-spin')} />刷新
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Metric label="在线" value={summary.online} icon={<Server className="h-4 w-4 text-emerald-300" />} />
        <Metric label="工作中" value={summary.working} icon={<Cpu className="h-4 w-4 text-violet-200" />} />
        <Metric label="空闲" value={summary.idle} icon={<HardDrive className="h-4 w-4 text-cyan-200" />} />
        <Metric label="离线" value={summary.offline} icon={<CircleOff className="h-4 w-4 text-[#8f88a8]" />} />
      </div>

      {error && <div className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-500"><AlertCircle className="h-4 w-4" />{error}</div>}

      {loading ? (
        <div className="grid place-items-center py-24"><Loader2 className="h-6 w-6 animate-spin text-violet-300" /></div>
      ) : workers.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-ink-200 py-20 text-center">
          <ServerCog className="mx-auto h-8 w-8 text-ink-400" />
          <p className="mt-3 text-sm text-ink-400">还没有 Worker 注册</p>
        </div>
      ) : (
        <div className="grid gap-4 xl:grid-cols-2">
          {workers.map(worker => {
            const meta = statusMeta[worker.status]
            return (
              <article key={worker.workerId} className="rounded-xl border border-ink-200 bg-white p-5 shadow-card">
                <div className="flex items-start gap-4">
                  <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-violet-500/15 text-violet-200"><ServerCog className="h-5 w-5" /></span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h3 className="truncate font-mono text-xs font-semibold text-ink-900" title={worker.workerId}>{worker.workerId}</h3>
                      <span className={cn('flex items-center gap-1.5 rounded-lg border px-2 py-1 text-[10px] font-medium', meta.color)}><span className={cn('h-1.5 w-1.5 rounded-full', meta.dot)} />{meta.label}</span>
                    </div>
                    <p className="mt-1 text-xs text-ink-400">{worker.hostName} · PID {worker.processId}</p>
                  </div>
                </div>

                <div className="mt-5 grid grid-cols-2 gap-4 border-t border-ink-100 pt-4 text-xs">
                  <Detail label="当前任务" value={worker.activeTaskId || '空闲'} mono />
                  <Detail label="最近心跳" value={formatTime(worker.heartbeatAt)} icon={<TimerReset className="h-3 w-3" />} />
                  <Detail label="启动时间" value={formatTime(worker.startedAt)} />
                  <Detail label="停止时间" value={formatTime(worker.stoppedAt)} />
                </div>

                {worker.lastError && (
                  <div className="mt-4 rounded-lg border border-rose-400/15 bg-rose-400/[0.06] p-3">
                    <p className="mb-1 text-[10px] uppercase tracking-wide text-rose-300">最近错误</p>
                    <p className="line-clamp-3 whitespace-pre-wrap font-mono text-[11px] leading-5 text-rose-200">{worker.lastError}</p>
                  </div>
                )}
              </article>
            )
          })}
        </div>
      )}
    </div>
  )
}

function Metric({ label, value, icon }: { label: string; value: number; icon: React.ReactNode }) {
  return <div className="flex items-center justify-between rounded-xl border border-ink-200 bg-white px-4 py-3"><div><p className="text-xs text-ink-400">{label}</p><p className="mt-1 text-xl font-semibold text-ink-900">{value}</p></div>{icon}</div>
}

function Detail({ label, value, icon, mono = false }: { label: string; value: string; icon?: React.ReactNode; mono?: boolean }) {
  return <div className="min-w-0"><p className="text-[10px] uppercase tracking-wide text-ink-400">{label}</p><p className={cn('mt-1 flex items-center gap-1.5 truncate text-ink-600', mono && 'font-mono text-[11px]')} title={value}>{icon}{value}</p></div>
}

function formatTime(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
