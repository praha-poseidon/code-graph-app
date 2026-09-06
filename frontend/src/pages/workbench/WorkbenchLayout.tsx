import type { WorkbenchController } from './state/useWorkbenchState'
import Header from './header/Header'
import BottomBar from './bottom/BottomBar'
import Canvas from './canvas/Canvas'
import CodeAi from './right/CodeAi'

export default function WorkbenchLayout({ controller }: { controller: WorkbenchController }) {
  return (
    <div className="flex h-full min-w-0 flex-1 flex-col overflow-hidden bg-[#07080f] text-[#f7f2ff]">
      <Header controller={controller} />

      <div className="flex min-h-0 flex-1 overflow-hidden">
        <Canvas controller={controller} />
        {controller.aiOpen && <CodeAi controller={controller} />}
      </div>

      <BottomBar controller={controller} />
    </div>
  )
}
