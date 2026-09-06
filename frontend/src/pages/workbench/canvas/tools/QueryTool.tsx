import { ChevronDown, Play, Terminal, X } from 'lucide-react'
import type { WorkbenchController } from '../../state/useWorkbenchState'

export default function QueryTool({ controller }: { controller: WorkbenchController }) {
  return (
    <>
      <button
        onClick={() => controller.setQueryOpen(true)}
        className="absolute bottom-4 left-6 z-20 flex h-11 items-center gap-2 rounded-xl bg-gradient-to-br from-[#06d6e8] to-[#00b8a9] px-5 text-xs font-bold text-white shadow-[0_0_26px_rgba(6,214,232,0.26)]"
      >
        <Terminal className="h-4 w-4" />
        Query
      </button>

      {controller.queryOpen && (
        <div className="absolute bottom-[72px] left-6 z-30 w-[440px] overflow-hidden rounded-xl border border-cyan-500/35 bg-[#090a12]/95 shadow-[0_0_24px_rgba(6,182,212,0.12)] backdrop-blur">
          <div className="flex h-11 items-center justify-between border-b border-white/10 px-3.5">
            <div className="flex items-center gap-3">
              <span className="grid h-7 w-7 place-items-center rounded-lg bg-gradient-to-br from-[#06d6e8] to-[#00b8a9] shadow-[0_0_14px_rgba(6,214,232,0.18)]">
                <Terminal className="h-3.5 w-3.5 text-white" />
              </span>
              <div className="text-xs font-bold text-white">Cypher Query</div>
            </div>
            <button
              onClick={() => controller.setQueryOpen(false)}
              className="grid h-7 w-7 place-items-center rounded-lg text-[#77718f] transition hover:bg-white/[0.06] hover:text-white"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <div className="space-y-3 p-3.5">
            <textarea
              value={controller.cypher}
              onChange={event => controller.setCypher(event.target.value)}
              rows={3}
              className="w-full resize-none rounded-lg border border-cyan-500/40 bg-[#151620] p-3 font-mono text-xs leading-5 text-[#f1ecff] outline-none shadow-[inset_0_0_0_1px_rgba(6,182,212,0.12)] transition placeholder:text-[#77718f] focus:border-cyan-300/70 focus:shadow-[inset_0_0_0_1px_rgba(6,182,212,0.2),0_0_14px_rgba(6,182,212,0.10)]"
            />

            <div className="flex items-center justify-between gap-4">
              <button className="flex h-8 items-center gap-2 rounded-lg bg-white/[0.08] px-3 text-xs font-semibold text-[#dcd5ef] transition hover:bg-white/[0.12] hover:text-white">
                <span className="text-violet-200">✣</span>
                Examples
                <ChevronDown className="h-3.5 w-3.5 text-[#9d97b6]" />
              </button>

              <div className="flex items-center gap-3">
                <button
                  onClick={() => controller.setCypher('')}
                  className="h-9 px-2 text-xs font-medium text-[#8f88a8] transition hover:text-white"
                >
                  Clear
                </button>
                <button
                  onClick={controller.runCypherQuery}
                  disabled={controller.querying}
                  className="flex h-9 items-center gap-2 rounded-lg bg-gradient-to-br from-[#06d6e8] to-[#00b8a9] px-4 text-xs font-bold text-white shadow-[0_0_16px_rgba(6,214,232,0.18)] transition hover:brightness-110 disabled:opacity-50"
                >
                  <Play className="h-3.5 w-3.5" />
                  {controller.querying ? 'Running...' : 'Run'}
                  <span className="rounded bg-white/15 px-1.5 py-0.5 text-[10px] text-cyan-50">⌘↵</span>
                </button>
              </div>
            </div>

            <div className="text-[11px] text-white/30">
              Read-only MATCH queries. Return nodes, relationships, or paths.
            </div>
          </div>
        </div>
      )}
    </>
  )
}
