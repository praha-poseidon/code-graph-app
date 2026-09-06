import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import Sigma from 'sigma'
import Graph from 'graphology'
import forceAtlas2 from 'graphology-layout-forceatlas2'
import noverlap from 'graphology-layout-noverlap'
import { EdgeArrowProgram } from 'sigma/rendering'
import type { GraphData, GraphNode } from '../../../types/graph'
import { graphDataToSigma, type SigmaEdgeAttributes, type SigmaNodeAttributes } from './sigmaAdapter'

interface GraphCanvasProps {
  data: GraphData
  selectedNodeId: string | null
  traceRootNodeId?: string | null
  /** FORWARD: follow outgoing edges (caller -> callee). BACKWARD: incoming. BOTH: undirected layers. */
  traceDirection?: 'FORWARD' | 'BACKWARD' | 'BOTH'
  onNodeSelect: (node: GraphNode | null) => void
  renderToolbar?: (controls: SigmaCanvasControls) => ReactNode
}

export interface SigmaCanvasControls {
  zoomIn: () => void
  zoomOut: () => void
  resetZoom: () => void
  rerunLayout: () => void
}

const dimColor = (hex: string, amount: number) => {
  const value = hex.replace('#', '')
  const r = parseInt(value.slice(0, 2), 16)
  const g = parseInt(value.slice(2, 4), 16)
  const b = parseInt(value.slice(4, 6), 16)
  const base = { r: 18, g: 18, b: 28 }
  const mix = (channel: number, baseChannel: number) =>
    Math.round(baseChannel + (channel - baseChannel) * amount).toString(16).padStart(2, '0')
  return `#${mix(r, base.r)}${mix(g, base.g)}${mix(b, base.b)}`
}

export default function GraphCanvas({
  data,
  selectedNodeId,
  traceRootNodeId,
  traceDirection = 'BOTH',
  onNodeSelect,
  renderToolbar,
}: GraphCanvasProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const sigmaRef = useRef<Sigma | null>(null)
  const graphRef = useRef<Graph<SigmaNodeAttributes, SigmaEdgeAttributes> | null>(null)
  const selectedRef = useRef<string | null>(null)
  const [hoveredNode, setHoveredNode] = useState<string | null>(null)

  const nodeMap = useMemo(() => {
    return new Map(data.nodes.map((node) => [node.id, node]))
  }, [data.nodes])

  useEffect(() => {
    selectedRef.current = selectedNodeId
    sigmaRef.current?.refresh()
  }, [selectedNodeId])

  useEffect(() => {
    if (!containerRef.current) return

    const graph = new Graph<SigmaNodeAttributes, SigmaEdgeAttributes>()
    graphRef.current = graph

    const sigma = new Sigma(graph, containerRef.current, {
      renderLabels: true,
      renderEdgeLabels: true,
      enableEdgeEvents: true,
      labelFont: 'PingFang SC, JetBrains Mono, monospace',
      labelSize: 11,
      labelWeight: '500',
      labelColor: { color: '#e4e4ed' },
      labelRenderedSizeThreshold: 4,
      labelDensity: 0.2,
      labelGridCellSize: 64,
      edgeLabelFont: 'JetBrains Mono, monospace',
      edgeLabelSize: 9,
      edgeLabelWeight: '600',
      edgeLabelColor: { color: '#c4b5fd' },
      defaultNodeColor: '#6b7280',
      defaultEdgeColor: '#2a2a3a',
      defaultEdgeType: 'arrow',
      edgeProgramClasses: {
        arrow: EdgeArrowProgram,
      },
      hideEdgesOnMove: false,
      zIndex: true,
      defaultDrawNodeHover: (context, data, settings) => {
        const label = data.label
        if (!label) return
        const size = settings.labelSize || 11
        context.font = `600 ${size}px PingFang SC, sans-serif`
        const width = context.measureText(label).width + 18
        const height = size + 12
        const x = data.x
        const y = data.y - (data.size || 8) - 13

        context.fillStyle = '#12121c'
        context.beginPath()
        context.roundRect(x - width / 2, y - height / 2, width, height, 5)
        context.fill()
        context.strokeStyle = data.color || '#8b5cf6'
        context.lineWidth = 2
        context.stroke()
        context.fillStyle = '#f5f5f7'
        context.textAlign = 'center'
        context.textBaseline = 'middle'
        context.fillText(label, x, y)

        context.beginPath()
        context.arc(data.x, data.y, (data.size || 8) + 4, 0, Math.PI * 2)
        context.strokeStyle = data.color || '#8b5cf6'
        context.globalAlpha = 0.5
        context.stroke()
        context.globalAlpha = 1
      },
      nodeReducer: (node, data) => {
        const result = { ...data }
        if (data.isControlPoint) {
          result.size = 0
          result.label = ''
          result.zIndex = -1
          return result
        }
        const selected = selectedRef.current
        if (!selected) return result

        const graph = graphRef.current
        const isSelected = node === selected
        const isNeighbor = graph?.hasEdge(node, selected) || graph?.hasEdge(selected, node)

        if (isSelected) {
          result.size = (data.size || 8) * 1.9
          result.zIndex = 3
        } else if (isNeighbor) {
          result.size = (data.size || 8) * 1.3
          result.zIndex = 2
        } else {
          result.color = dimColor(data.color || '#6b7280', 0.22)
          result.size = (data.size || 8) * 0.65
          result.zIndex = 0
        }
        return result
      },
      edgeReducer: (edge, data) => {
        const result = { ...data }
        const selected = selectedRef.current
        if (!selected) return result

        const graph = graphRef.current
        const [source, target] = graph?.extremities(edge) || []
        const connected = source === selected || target === selected
        if (connected) {
          result.size = Math.max(2.5, (data.size || 1) * 3)
          result.zIndex = 2
        } else {
          result.color = dimColor(data.color || '#4a4a5a', 0.12)
          result.size = 0.3
          result.zIndex = 0
        }
        return result
      },
    })

    sigma.on('clickNode', ({ node }) => {
      onNodeSelect(nodeMap.get(node) || null)
    })
    sigma.on('clickStage', () => onNodeSelect(null))
    sigma.on('enterNode', ({ node }) => {
      setHoveredNode(nodeMap.get(node)?.label || null)
      if (containerRef.current) containerRef.current.style.cursor = 'pointer'
    })
    sigma.on('leaveNode', () => {
      setHoveredNode(null)
      if (containerRef.current) containerRef.current.style.cursor = 'grab'
    })

    sigmaRef.current = sigma

    return () => {
      sigma.kill()
      sigmaRef.current = null
      graphRef.current = null
    }
  }, [nodeMap, onNodeSelect])

  const runDirectedLayout = useCallback((
    graph: Graph<SigmaNodeAttributes, SigmaEdgeAttributes>,
    rootId: string,
    direction: 'FORWARD' | 'BACKWARD' | 'BOTH' = 'FORWARD',
  ) => {
    if (graph.order === 0) return

    const ranks = new Map<string, number>()
    const queue: string[] = []
    const root = graph.hasNode(rootId) ? rootId : graph.nodes()[0]

    ranks.set(root, 0)
    queue.push(root)

    const nextNodes = (node: string): string[] => {
      if (direction === 'FORWARD') return graph.outNeighbors(node)
      if (direction === 'BACKWARD') return graph.inNeighbors(node)
      return graph.neighbors(node)
    }

    while (queue.length) {
      const node = queue.shift()
      if (!node) continue
      const nextRank = (ranks.get(node) || 0) + 1
      nextNodes(node).forEach((neighbor) => {
        if (ranks.has(neighbor)) return
        ranks.set(neighbor, nextRank)
        queue.push(neighbor)
      })
    }

    const disconnectedRank = Math.max(1, ...Array.from(ranks.values())) + 1
    graph.nodes().forEach(node => {
      if (!ranks.has(node)) ranks.set(node, disconnectedRank)
    })

    const layers = new Map<number, string[]>()
    ranks.forEach((rank, node) => {
      const layer = layers.get(rank) || []
      layer.push(node)
      layers.set(rank, layer)
    })

    const nodeGap = graph.order > 120 ? 34 : 48
    const rankGap = graph.order > 120 ? 150 : 180
    const layerCount = layers.size
    const minRank = Math.min(...Array.from(layers.keys()))
    const maxRank = Math.max(...Array.from(layers.keys()))
    const centerOffset = ((maxRank - minRank) * rankGap) / 2

    Array.from(layers.entries())
      .sort(([a], [b]) => a - b)
      .forEach(([rank, nodes]) => {
        nodes.sort((a, b) => String(graph.getNodeAttribute(a, 'label')).localeCompare(String(graph.getNodeAttribute(b, 'label'))))
        const columnHeight = (nodes.length - 1) * nodeGap
        nodes.forEach((node, index) => {
          const stagger = layerCount > 2 && index % 2 ? 10 : -10
          graph.setNodeAttribute(node, 'x', (rank - minRank) * rankGap - centerOffset)
          graph.setNodeAttribute(node, 'y', index * nodeGap - columnHeight / 2 + stagger)
        })
      })

    // Keep arrows as straight directed edges for call-direction readability.
  }, [])

  const runLayout = useCallback((graph: Graph<SigmaNodeAttributes, SigmaEdgeAttributes>) => {
    if (graph.order === 0) return
    const inferred = forceAtlas2.inferSettings(graph)
    forceAtlas2.assign(graph, {
      iterations: graph.order > 1000 ? 90 : 140,
      settings: {
        ...inferred,
        gravity: graph.order > 500 ? 0.45 : 0.8,
        scalingRatio: graph.order > 500 ? 42 : 22,
        slowDown: graph.order > 500 ? 3 : 1.5,
        barnesHutOptimize: graph.order > 200,
        outboundAttractionDistribution: true,
        adjustSizes: true,
      },
    })
    noverlap.assign(graph, {
      maxIterations: 24,
      settings: {
        ratio: 1.15,
        margin: 8,
        expansion: 1.05,
      },
    })
  }, [])

  const setSigmaGraph = useCallback(() => {
    const sigma = sigmaRef.current
    if (!sigma) return
    const graph = graphDataToSigma(data)
    graphRef.current = graph
    if (traceRootNodeId) {
      // Trace layout layers follow selected direction (call direction when CALLS edges present).
      runDirectedLayout(graph, traceRootNodeId, traceDirection === 'BOTH' ? 'FORWARD' : traceDirection)
    } else {
      runLayout(graph)
    }
    sigma.setGraph(graph)
    sigma.getCamera().animatedReset({ duration: 350 })
  }, [data, runDirectedLayout, runLayout, traceDirection, traceRootNodeId])

  useEffect(() => {
    setSigmaGraph()
  }, [setSigmaGraph])

  const zoomIn = () => sigmaRef.current?.getCamera().animatedZoom({ duration: 180 })
  const zoomOut = () => sigmaRef.current?.getCamera().animatedUnzoom({ duration: 180 })
  const resetZoom = () => sigmaRef.current?.getCamera().animatedReset({ duration: 260 })
  const rerunLayout = () => setSigmaGraph()

  return (
    <div className="relative h-full w-full">
      <div ref={containerRef} className="h-full w-full cursor-grab active:cursor-grabbing" />

      {hoveredNode && !selectedNodeId && (
        <div className="pointer-events-none absolute left-1/2 top-4 z-20 -translate-x-1/2 rounded-lg border border-white/10 bg-[#12121c]/95 px-3 py-1.5 text-sm font-mono text-white shadow-xl">
          {hoveredNode}
        </div>
      )}

      {renderToolbar?.({ zoomIn, zoomOut, resetZoom, rerunLayout })}
    </div>
  )
}
