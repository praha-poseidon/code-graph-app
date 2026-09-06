import { Filter } from 'lucide-react'
import type { WorkbenchController } from '../../state/useWorkbenchState'

export default function FilterTool({ controller }: { controller: WorkbenchController }) {
  return (
    <button
      onClick={() => controller.setFilterOpen(open => !open)}
      title="Filters"
      className={`grid h-10 w-10 place-items-center rounded-lg border transition ${
        controller.filterOpen
          ? 'border-violet-400/60 bg-violet-600/20 text-violet-200'
          : 'border-white/10 bg-white/[0.06] text-[#9d97b6] hover:text-white'
      }`}
    >
      <Filter className="h-4 w-4" />
    </button>
  )
}
