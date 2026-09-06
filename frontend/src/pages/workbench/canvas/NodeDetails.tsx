import { GitBranch, Play, X } from 'lucide-react'
import { useState } from 'react'
import type { WorkbenchController } from '../state/useWorkbenchState'

function DetailRow({ label, value }: { label: string; value?: string | number | null }) {
  if (value === undefined || value === null || value === '') return null

  return (
    <div className="space-y-1">
      <div className="text-[10px] font-semibold uppercase tracking-wide text-[#77718f]">{label}</div>
      <div className="break-words rounded-lg bg-white/[0.045] px-3 py-2 font-mono text-[11px] leading-5 text-[#dcd5ef]">
        {value}
      </div>
    </div>
  )
}

export default function NodeDetails({ controller }: { controller: WorkbenchController }) {
  const [copyStatus, setCopyStatus] = useState('')
  const node = controller.selectedNode
  if (!node) return null
  const properties = node.properties ?? { id: node.id, name: node.fullName, type: node.type }
  const value = (key: string) => properties[key] == null ? undefined : String(properties[key])

  return (
    <aside className="absolute right-5 top-5 z-40 flex max-h-[calc(100%-40px)] w-[400px] max-w-[calc(100%-40px)] flex-col overflow-hidden rounded-xl border border-white/12 bg-[#10111d]/96 shadow-2xl backdrop-blur">
      <div className="flex min-h-12 items-center justify-between gap-3 border-b border-white/10 px-3.5">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="h-2 w-2 shrink-0 rounded-full bg-violet-300" />
            <div className="truncate text-sm font-bold text-white">{node.label}</div>
            <span className="shrink-0 rounded-md bg-white/[0.08] px-2 py-1 text-[10px] font-semibold text-[#9d97b6]">
              {node.type}
            </span>
          </div>
        </div>
        <button
          onClick={() => controller.setSelectedNode(null)}
          className="grid h-8 w-8 shrink-0 place-items-center rounded-lg text-[#77718f] transition hover:bg-white/[0.08] hover:text-white"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3.5">
        <DetailRow label="ID" value={node.id} />
        <DetailRow label="完整名称" value={node.qualifiedName || node.fullName} />
        <DetailRow label="语言" value={value('language')} />
        <DetailRow label="项目标识" value={value('projectName')} />
        <DetailRow label="文件" value={node.filePath || node.path} />
        <DetailRow label="代码位置" value={properties.startLine != null ? `${value('startLine')}–${value('endLine') ?? value('startLine')} 行` : undefined} />
        <DetailRow label="仓库" value={node.gitRepoUrl} />
        <DetailRow label="分支" value={value('gitBranch')} />
        <DetailRow label="签名" value={value('signature')} />
        <DetailRow label="返回类型" value={value('returnType')} />
        <DetailRow label="HTTP 方法" value={node.httpMethod} />
        <details key={node.id} className="rounded-xl border border-white/10 p-3">
          <summary className="cursor-pointer text-xs font-medium text-[#dcd5ef]">全部属性（{Object.keys(properties).length}）</summary>
          <button type="button" onClick={async () => {
            try { await navigator.clipboard.writeText(JSON.stringify(properties, null, 2)); setCopyStatus('已复制') }
            catch { setCopyStatus('复制失败，请手动选择属性') }
          }} className="my-3 text-xs text-violet-300">复制 JSON</button>
          <span role="status" className="ml-2 text-[10px] text-[#9d97b6]">{copyStatus}</span>
          <dl className="divide-y divide-white/5">
            {Object.entries(properties).sort(([a], [b]) => a.localeCompare(b)).map(([key, entry]) => (
              <div key={key} className="py-2">
                <dt className="break-all text-[10px] text-[#9d97b6]">{key}</dt>
                <dd className="mt-1 whitespace-pre-wrap break-all font-mono text-[11px] text-[#dcd5ef]">{typeof entry === 'string' ? entry : JSON.stringify(entry, null, 2) ?? 'null'}</dd>
              </div>
            ))}
          </dl>
        </details>

        <section className="space-y-3 rounded-xl border border-white/10 bg-white/[0.025] p-3">
          <div className="flex items-center gap-2 text-[11px] font-semibold uppercase tracking-wide text-[#9d97b6]">
            <GitBranch className="h-3.5 w-3.5 text-violet-300" />
            Trace Node
          </div>
          {controller.traceRootNode && (
            <div className="truncate rounded-lg bg-violet-500/10 px-3 py-2 text-xs text-violet-100/80">
              Root: {controller.traceRootNode.label}
            </div>
          )}
          <div className="grid grid-cols-[1fr_112px] gap-2">
            <select
              value={controller.traceDirection}
              onChange={event => controller.setTraceDirection(event.target.value as 'FORWARD' | 'BACKWARD' | 'BOTH')}
              className="h-9 rounded-lg border border-white/10 bg-[#080912] px-3 text-xs text-[#dcd5ef] outline-none transition focus:border-violet-400/50"
            >
              <option value="FORWARD">Forward (caller → callee)</option>
              <option value="BACKWARD">Backward (callee ← caller)</option>
              <option value="BOTH">Both ways</option>
            </select>
            <select
              value={controller.traceDepth}
              onChange={event => controller.setTraceDepth(Number(event.target.value))}
              className="h-9 rounded-lg border border-white/10 bg-[#080912] px-3 text-xs text-[#dcd5ef] outline-none transition focus:border-violet-400/50"
            >
              {[1, 2, 3, 4].map(depth => (
                <option key={depth} value={depth}>Depth {depth}</option>
              ))}
            </select>
          </div>
          <button
            type="button"
            onClick={() => controller.startTraceFromNode(node)}
            disabled={controller.loadingGraph}
            className="flex h-9 w-full items-center justify-center gap-2 rounded-lg bg-violet-600 text-xs font-bold text-white transition hover:bg-violet-500 disabled:opacity-50"
          >
            <Play className="h-3.5 w-3.5" />
            Trace from this node
          </button>
          <div className="text-[11px] leading-4 text-[#77718f]">
            After a trace root is chosen, direction, depth, and filters refresh automatically.
          </div>
        </section>
      </div>
    </aside>
  )
}
