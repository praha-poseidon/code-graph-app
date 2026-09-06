import { useState } from 'react'
import McpButton from './McpButton'
import { Bot, ChevronDown, CircleHelp, Keyboard, Loader2, Search, Settings } from 'lucide-react'
import { nodeDisplayName } from '../../../api/graphMapper'
import type { WorkbenchController } from '../state/useWorkbenchState'
import { repoDisplayName } from '../workbench-constants'

export default function WorkbenchHeader({ controller }: { controller: WorkbenchController }) {
  const [repoOpen, setRepoOpen] = useState(false)
  const selectedRepoLabel = controller.selectedGitRepoUrl ? repoDisplayName(controller.selectedGitRepoUrl) : 'All repositories'

  return (
    <header className="relative z-40 flex h-16 shrink-0 items-center gap-4 border-b border-dashed border-violet-500/20 bg-[#090910] px-5">
      <div className="flex w-[220px] shrink-0 items-center">
        <div className="relative w-full">
          <button
            type="button"
            title={controller.selectedGitRepoUrl || undefined}
            onClick={() => setRepoOpen(open => !open)}
            className="flex h-11 w-full items-center gap-2 rounded-xl border border-white/[0.08] bg-white/[0.025] px-3 text-left text-xs text-[#9d97b6] transition hover:border-violet-400/30 hover:text-white"
          >
            <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500" />
            <span className="min-w-0 flex-1 truncate">{selectedRepoLabel}</span>
            <ChevronDown className={`h-3.5 w-3.5 shrink-0 transition ${repoOpen ? 'rotate-180' : ''}`} />
          </button>
          {repoOpen && (
            <div className="absolute left-0 top-[calc(100%+8px)] z-50 max-h-60 w-[220px] overflow-y-auto rounded-lg border border-white/10 bg-[#11111d]/96 p-1 shadow-2xl backdrop-blur">
              <button
                type="button"
                onClick={() => {
                  controller.changeProject('')
                  setRepoOpen(false)
                }}
                className="flex h-9 w-full items-center gap-2 rounded-lg px-3 text-left text-xs text-[#dcd5ef] transition hover:bg-white/[0.06]"
              >
                <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
                All repositories
              </button>
              {controller.metadata.gitRepoUrls.map(url => (
                <button
                  key={url}
                  type="button"
                  onClick={() => {
                    controller.changeProject(url)
                    setRepoOpen(false)
                  }}
                  className="flex h-9 w-full items-center gap-2 rounded-lg px-3 text-left text-xs text-[#9d97b6] transition hover:bg-white/[0.06] hover:text-white"
                >
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-500/70" />
                  <span className="min-w-0 flex-1 truncate">{repoDisplayName(url)}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="flex flex-1 justify-center">
        <div className="relative w-full max-w-[520px]">
          <Search className="absolute left-4 top-1/2 h-4 w-4 -translate-y-1/2 text-[#77718f]" />
          <input
            value={controller.query}
            onChange={event => controller.setQuery(event.target.value)}
            placeholder="Search nodes..."
            className="h-11 w-full rounded-xl border border-white/10 bg-[#11111d] pl-11 pr-14 text-sm text-[#e6dcff] outline-none transition placeholder:text-[#77718f] focus:border-[#8b5cf6]/60"
          />
          <div className="absolute right-4 top-1/2 flex h-6 -translate-y-1/2 items-center rounded-md border border-white/10 bg-white/[0.03] px-2 text-[10px] text-[#77718f]">
            <Keyboard className="mr-1 h-3 w-3" />
            K
          </div>
          {(controller.query.trim() || controller.searching) && (
            <div className="absolute left-0 right-0 top-[calc(100%+8px)] z-50 max-h-[360px] overflow-hidden rounded-xl border border-white/10 bg-[#11111d]/95 shadow-2xl backdrop-blur">
              <div className="flex items-center justify-between border-b border-white/[0.06] px-3 py-2 text-[11px] text-[#77718f]">
                <span>Search results</span>
                {controller.searching && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
              </div>
              <div className="max-h-[312px] overflow-y-auto py-1">
                {controller.query.trim() && !controller.searching && controller.results.length === 0 && (
                  <div className="px-4 py-8 text-center text-xs text-[#77718f]">No nodes found</div>
                )}
                {controller.results.map((node) => {
                  const id = node.id || node.elementId || node.qualifiedName || node.name || ''
                  return (
                    <button
                      key={id}
                      onClick={() => {
                        controller.setQuery('')
                        controller.loadNodeGraph(node)
                      }}
                      className="w-full border-b border-white/[0.04] px-3 py-2.5 text-left transition hover:bg-white/[0.04]"
                    >
                      <div className="flex items-center gap-2">
                        <span className="h-2 w-2 rounded-full bg-violet-400" />
                        <span className="min-w-0 truncate text-xs font-medium text-[#dcd5ef]">{nodeDisplayName(node)}</span>
                      </div>
                      <div className="mt-1 truncate pl-4 text-xs text-[#77718f]">
                        {node.qualifiedName || node.projectFilePath || id}
                      </div>
                    </button>
                  )
                })}
              </div>
            </div>
          )}
        </div>
      </div>

      <div className="flex items-center gap-3">
        <McpButton />
        <button
          onClick={() => controller.setMode('settings')}
          title="配置中心"
          className="grid h-9 w-9 place-items-center rounded-lg text-[#9d97b6] transition hover:bg-white/[0.06] hover:text-white"
        >
          <Settings className="h-[18px] w-[18px]" />
        </button>
        <button className="grid h-9 w-9 place-items-center rounded-lg text-[#9d97b6] transition hover:bg-white/[0.06] hover:text-white">
          <CircleHelp className="h-[18px] w-[18px]" />
        </button>
        <button
          onClick={() => controller.setAiOpen(open => !open)}
          className={`flex h-10 items-center gap-1.5 rounded-lg border px-4 text-sm font-semibold transition ${
            controller.aiOpen
              ? 'border-violet-400/50 bg-violet-600/20 text-white'
              : 'border-white/10 bg-white/[0.04] text-violet-200 hover:border-violet-400/40 hover:bg-violet-600/15 hover:text-white'
          }`}
        >
          <Bot className="h-4 w-4" />
          Code AI
        </button>
      </div>
    </header>
  )
}
