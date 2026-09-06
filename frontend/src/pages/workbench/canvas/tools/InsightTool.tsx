import { Lightbulb } from 'lucide-react'
import type { WorkbenchController } from '../../state/useWorkbenchState'

export default function InsightTool({ controller }: { controller: WorkbenchController }) {
  return (
    <button
      onClick={() => controller.setInsightOpen(open => !open)}
      title="Insights"
      className={`grid h-10 w-10 place-items-center rounded-lg border transition ${
        controller.insightOpen
          ? 'border-cyan-300/50 bg-cyan-500/15 text-cyan-200'
          : 'border-white/10 bg-white/[0.06] text-[#9d97b6] hover:text-white'
      }`}
    >
      <Lightbulb className="h-4 w-4" />
    </button>
  )
}
