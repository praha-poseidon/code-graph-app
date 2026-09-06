import { Filter, Lightbulb, X, Network } from 'lucide-react'
import type { WorkbenchController } from '../state/useWorkbenchState'
import { EDGE_FILTER_META, NODE_FILTER_META } from '../workbench-constants'

export default function CanvasOverlays({ controller }: { controller: WorkbenchController }) {
  return (
    <>
      {controller.loadingGraph && (
        <div className="absolute inset-0 z-30 grid place-items-center bg-[#05060c]/60 backdrop-blur-sm">
          <div className="flex items-center gap-3 rounded-xl border border-white/10 bg-[#11111d] px-5 py-3 text-sm text-[#dcd5ef]">
            <div className="h-4 w-4 animate-spin rounded-full border-2 border-violet-300 border-t-transparent" />
            Loading graph...
          </div>
        </div>
      )}

      {controller.error && (
        <div className="absolute left-6 top-6 z-30 max-w-md rounded-xl border border-red-500/30 bg-red-500/10 px-4 py-3 text-sm text-red-200">
          {controller.error}
        </div>
      )}

      {controller.traceRootNode && (
        <div className="absolute left-6 top-6 z-20 flex max-w-[420px] items-center gap-2 rounded-xl border border-violet-500/30 bg-violet-500/10 px-3 py-2 text-xs text-violet-100 shadow-[0_0_20px_rgba(139,92,246,0.14)]">
          <span className="h-2 w-2 shrink-0 rounded-full bg-violet-300" />
          <span className="shrink-0 font-semibold">Tracing</span>
          <span className="truncate text-violet-100/70">{controller.traceRootNode.label}</span>
        </div>
      )}

      {controller.insightOpen && (
        <div className="absolute bottom-[328px] right-20 z-50 w-[240px] overflow-hidden rounded-xl border border-cyan-300/20 bg-[#11111d]/95 shadow-2xl backdrop-blur">
          <div className="flex items-center justify-between border-b border-white/10 px-4 py-3">
            <div className="flex items-center gap-2 text-xs font-semibold text-cyan-100">
              <Lightbulb className="h-4 w-4 text-cyan-200" />
              Graph insights
            </div>
            <button onClick={() => controller.setInsightOpen(false)} className="rounded p-1 text-white/35 hover:bg-white/10 hover:text-white">
              <X className="h-4 w-4" />
            </button>
          </div>
          <div className="space-y-3 p-4 text-xs leading-5 text-[#9d97b6]">
            <p>Use search to pin a root node, then adjust trace depth and filters to narrow the code path.</p>
            <div className="rounded-lg border border-cyan-300/15 bg-cyan-500/10 px-3 py-2 text-cyan-100/80">
              Current graph: {controller.graphData.nodes.length} nodes / {controller.graphData.edges.length} edges
            </div>
          </div>
        </div>
      )}

      {controller.filterOpen && (
        <div className="absolute bottom-16 right-20 top-5 z-50 flex w-[280px] flex-col overflow-hidden rounded-xl border border-white/10 bg-[#11111d]/95 shadow-2xl backdrop-blur">
          <div className="flex items-center justify-between border-b border-white/10 px-3.5 py-2.5">
            <div className="flex items-center gap-2 text-xs font-semibold text-white">
              <Filter className="h-4 w-4" />
              Filters
            </div>
            <button onClick={() => controller.setFilterOpen(false)} className="rounded p-1 text-white/35 hover:bg-white/10 hover:text-white">
              <X className="h-4 w-4" />
            </button>
          </div>
          <div className="min-h-0 flex-1 space-y-3 overflow-y-auto p-3">
            <section className="space-y-2">
              <div>
                <div className="text-[11px] font-semibold uppercase tracking-wide text-white/55">Node Types</div>
                <div className="mt-1 text-[11px] text-[#77718f]">Toggle visibility of node types</div>
              </div>
              <div className="space-y-2">
                {controller.metadata.nodeTypes.map(type => {
                  const meta = NODE_FILTER_META[type] || { label: type, color: '#8b5cf6', icon: Network }
                  const Icon = meta.icon
                  return (
                    <button
                      key={type}
                      type="button"
                      onClick={() => controller.toggleNodeType(type)}
                      className={`flex h-10 w-full items-center gap-2.5 rounded-lg border px-3 text-left transition ${
                        controller.selectedNodeTypes.includes(type)
                          ? 'border-transparent bg-white/[0.045] text-[#f1ecff]'
                          : 'border-transparent bg-white/[0.025] text-[#68627f]'
                      }`}
                    >
                      <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md" style={{ background: `${meta.color}22`, color: meta.color }}>
                        <Icon className="h-3.5 w-3.5" />
                      </span>
                      <span className="min-w-0 flex-1 truncate text-xs font-medium">{meta.label}</span>
                      <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: controller.selectedNodeTypes.includes(type) ? meta.color : '#242234' }} />
                    </button>
                  )
                })}
              </div>
            </section>

            <section className="space-y-2 border-t border-white/10 pt-3">
              <div>
                <div className="text-[11px] font-semibold uppercase tracking-wide text-white/55">Edge Types</div>
                <div className="mt-1 text-[11px] text-[#77718f]">Toggle relationship types</div>
              </div>
              <div className="max-h-56 space-y-2 overflow-y-auto">
                {controller.metadata.relationshipTypes.map(type => {
                  const meta = EDGE_FILTER_META[type] || { label: type, color: '#8b5cf6' }
                  return (
                    <button
                      key={type}
                      type="button"
                      onClick={() => controller.toggleValue(type, controller.selectedRelationshipTypes, controller.selectedRelationshipTypesSetter)}
                      className={`flex h-10 w-full items-center gap-2.5 rounded-lg border px-3 text-left transition ${
                        controller.selectedRelationshipTypes.includes(type)
                          ? 'border-transparent bg-white/[0.045] text-[#f1ecff]'
                          : 'border-transparent bg-white/[0.025] text-[#68627f]'
                      }`}
                    >
                      <span className="grid h-7 w-7 shrink-0 place-items-center rounded-md bg-white/[0.02]">
                        <span className="h-1.5 w-4 rounded-full" style={{ background: controller.selectedRelationshipTypes.includes(type) ? meta.color : '#252336' }} />
                      </span>
                      <span className="min-w-0 flex-1 truncate text-xs font-medium leading-none">{meta.label}</span>
                      <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: controller.selectedRelationshipTypes.includes(type) ? meta.color : '#242234' }} />
                    </button>
                  )
                })}
              </div>
            </section>

            <div className="rounded-lg border border-white/10 bg-white/[0.025] px-3 py-2 text-[11px] leading-4 text-[#77718f]">
              Changes refresh the graph automatically.
            </div>
          </div>
        </div>
      )}
    </>
  )
}
