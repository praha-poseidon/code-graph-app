import GraphCanvas from './GraphCanvas'
import type { WorkbenchController } from '../state/useWorkbenchState'
import CanvasOverlays from './CanvasOverlays'
import NodeDetails from './NodeDetails'
import QueryTool from './tools/QueryTool'
import CanvasTools from './tools/CanvasTools'

export default function Canvas({ controller }: { controller: WorkbenchController }) {
  return (
    <main className="flex min-w-0 flex-1 flex-col overflow-hidden bg-[radial-gradient(circle_at_center,rgba(88,28,135,0.10),transparent_42%),#05060c]">
      <div className="relative min-h-0 flex-1 overflow-hidden">
        <GraphCanvas
          data={controller.graphData}
          selectedNodeId={controller.selectedNode?.id || null}
          traceRootNodeId={controller.traceRootNode?.id || null}
          traceDirection={controller.traceDirection}
          onNodeSelect={controller.setSelectedNode}
          renderToolbar={(controls) => (
            <CanvasTools
              controls={controls}
              controller={controller}
            />
          )}
        />
        <CanvasOverlays controller={controller} />
        <NodeDetails controller={controller} />
        <QueryTool controller={controller} />
      </div>
    </main>
  )
}
