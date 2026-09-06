import type { WorkbenchController } from '../state/useWorkbenchState'

export default function BottomBar({ controller }: { controller: WorkbenchController }) {
  return (
    <div className="z-10 flex h-11 items-center justify-between border-t border-white/[0.06] bg-[#080912]/90 px-5 text-xs text-[#77718f]">
      <div className="flex items-center gap-2">
        <span className="h-2 w-2 rounded-full bg-emerald-400" />
        Ready
      </div>
      <div className="flex items-center gap-4">
        <span>{controller.graphData.nodes.length} nodes</span>
        <span>·</span>
        <span>{controller.graphData.edges.length} edges</span>
      </div>
    </div>
  )
}
