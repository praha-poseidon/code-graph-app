import { Maximize2, MinusCircle, Play, ZoomIn } from 'lucide-react'
import type { SigmaCanvasControls } from '../GraphCanvas'
import type { WorkbenchController } from '../../state/useWorkbenchState'
import FilterTool from './FilterTool'
import InsightTool from './InsightTool'

export default function CanvasTools({ controls, controller }: { controls: SigmaCanvasControls; controller: WorkbenchController }) {
  return (
    <div className="absolute bottom-4 right-6 z-20 flex flex-col gap-2">
      <InsightTool controller={controller} />
      <FilterTool controller={controller} />
      <div className="my-2 h-px bg-white/10" />
      <button onClick={controls.zoomIn} className="grid h-10 w-10 place-items-center rounded-lg border border-white/10 bg-white/[0.06] text-[#9d97b6] transition hover:text-white">
        <ZoomIn className="h-4 w-4" />
      </button>
      <button onClick={controls.zoomOut} className="grid h-10 w-10 place-items-center rounded-lg border border-white/10 bg-white/[0.06] text-[#9d97b6] transition hover:text-white">
        <MinusCircle className="h-4 w-4" />
      </button>
      <button onClick={controls.resetZoom} className="grid h-10 w-10 place-items-center rounded-lg border border-white/10 bg-white/[0.06] text-[#9d97b6] transition hover:text-white">
        <Maximize2 className="h-4 w-4" />
      </button>
      <div className="my-2 h-px bg-white/10" />
      <button onClick={controls.rerunLayout} className="grid h-10 w-10 place-items-center rounded-lg border border-white/10 bg-white/[0.06] text-[#9d97b6] transition hover:text-white">
        <Play className="h-4 w-4" />
      </button>
    </div>
  )
}
