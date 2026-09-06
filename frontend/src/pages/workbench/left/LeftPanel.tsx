import { Folder, PanelLeft, Search } from 'lucide-react'
import type { WorkbenchController } from '../state/useWorkbenchState'

export default function LeftPanel({ controller }: { controller: WorkbenchController }) {
  return (
    <aside className="relative z-30 flex shrink-0 border-r border-white/[0.06] bg-[#090910]">
      <div className="flex w-14 flex-col items-center gap-3 py-4">
        <button
          onClick={() => controller.setLeftPanelOpen(open => !open)}
          title="Left panel"
          className={`grid h-10 w-10 place-items-center rounded-lg border transition ${
            controller.leftPanelOpen
              ? 'border-violet-400/40 bg-violet-600/20 text-violet-200'
              : 'border-white/10 bg-white/[0.035] text-[#9d97b6] hover:bg-white/[0.06] hover:text-white'
          }`}
        >
          <PanelLeft className="h-[18px] w-[18px]" />
        </button>
      </div>

      {controller.leftPanelOpen && (
        <div className="w-56 border-l border-white/[0.04] bg-[#10111d]/96 shadow-2xl backdrop-blur">
          <div className="flex h-10 items-center gap-2 border-b border-white/10 px-3 text-xs font-semibold text-white">
            <Folder className="h-4 w-4 text-violet-300" />
            Workspace
          </div>
          <div className="space-y-3 p-3">
            <div className="flex h-9 items-center gap-2 rounded-lg border border-white/10 bg-white/[0.035] px-3 text-xs text-[#77718f]">
              <Search className="h-3.5 w-3.5" />
              Reserved panel
            </div>
            <div className="rounded-lg border border-white/10 bg-white/[0.025] px-3 py-3 text-xs leading-5 text-[#8f88a8]">
              Reserved for project navigation, files, or custom graph views.
            </div>
          </div>
        </div>
      )}
    </aside>
  )
}
