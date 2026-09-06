import { Bot, PanelRightClose, Send } from 'lucide-react'
import type { WorkbenchController } from '../state/useWorkbenchState'

export default function CodeAi({ controller }: { controller: WorkbenchController }) {
  return (
    <aside
      className="relative z-40 flex h-full shrink-0 flex-col overflow-hidden border-l border-white/[0.06] bg-[#0d0d16]/96 backdrop-blur"
      style={{ width: `${controller.aiWidth}px` }}
    >
      <div
        onPointerDown={controller.beginAiResize}
        className="absolute left-0 top-0 z-50 h-full w-1 cursor-col-resize bg-transparent hover:bg-violet-400/40"
        title="Drag to resize"
      />
      <div className="flex h-16 shrink-0 items-center justify-between border-b border-white/10 px-5 pl-6">
        <button className="flex h-10 items-center gap-2 rounded-lg bg-violet-600/20 px-3 text-sm font-semibold text-violet-200">
          <Bot className="h-4 w-4" />
          Code AI
        </button>
        <button
          onClick={() => controller.setAiOpen(false)}
          title="Close Code AI"
          className="grid h-9 w-9 place-items-center rounded-lg text-[#77718f] transition hover:bg-white/[0.06] hover:text-white"
        >
          <PanelRightClose className="h-5 w-5" />
        </button>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto p-6" />

      <div className="shrink-0 border-t border-white/10 p-5">
        <div className="flex h-14 items-center gap-3 border border-white/10 bg-white/[0.035] px-4">
          <input
            disabled
            placeholder="Ask about the codebase..."
            className="min-w-0 flex-1 bg-transparent text-sm text-[#e6dcff] outline-none placeholder:text-[#77718f]"
          />
          <button className="text-xs text-[#77718f]">Clear</button>
          <button className="grid h-10 w-10 place-items-center bg-violet-600 text-white opacity-70">
            <Send className="h-4 w-4" />
          </button>
        </div>
      </div>
    </aside>
  )
}
