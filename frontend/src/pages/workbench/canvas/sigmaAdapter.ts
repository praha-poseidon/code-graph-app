import Graph from 'graphology'
import type { GraphData, GraphNode } from '../../../types/graph'
import { nodeCaption, normalizeNodeType } from '../../../api/graphMapper'

export interface SigmaNodeAttributes {
  x: number
  y: number
  size: number
  color: string
  label: string
  nodeType: string
  filePath?: string
  qualifiedName?: string
  depth?: number
  isControlPoint?: boolean
  zIndex?: number
}

export interface SigmaEdgeAttributes {
  size: number
  color: string
  label: string
  relationType: string
  type?: string
  zIndex?: number
}

const NODE_COLORS: Record<string, string> = {
  CodeEndpoint: '#3b82f6',
  CodeFunction: '#10b981',
  CodeUnit: '#f59e0b',
  CodePackage: '#8b5cf6',
  CodeElement: '#64748b',
}

const NODE_SIZES: Record<string, number> = {
  CodeEndpoint: 10,
  CodeFunction: 6,
  CodeUnit: 9,
  CodePackage: 13,
  CodeElement: 5,
}

const EDGE_STYLES: Record<string, { color: string; size: number }> = {
  CALLS: { color: '#7c3aed', size: 1.6 },
  PACKAGE_TO_UNIT: { color: '#2d5a3d', size: 0.9 },
  UNIT_TO_FUNCTION: { color: '#0e7490', size: 1.1 },
  ENDPOINT_TO_FUNCTION: { color: '#1d4ed8', size: 1.4 },
  FUNCTION_TO_ENDPOINT: { color: '#be185d', size: 1.3 },
  EXTENDS: { color: '#ca8a04', size: 1.1 },
  IMPLEMENTS: { color: '#0891b2', size: 1.1 },
  OVERRIDES: { color: '#db2777', size: 1.2 },
  MATCHES: { color: '#c2410c', size: 1 },
}

const getNodeColor = (node: GraphNode) => NODE_COLORS[normalizeNodeType(node.type)] || '#9ca3af'
const getNodeSize = (node: GraphNode) => NODE_SIZES[normalizeNodeType(node.type)] || 6

export const graphDataToSigma = (data: GraphData) => {
  // Directed multi-graph so CALLS orientation and parallel relation types are preserved.
  const graph = new Graph<SigmaNodeAttributes, SigmaEdgeAttributes>({
    type: 'directed',
    multi: true,
    allowSelfLoops: true,
  })
  const count = Math.max(data.nodes.length, 1)
  const spread = Math.sqrt(count) * 80
  const goldenAngle = Math.PI * (3 - Math.sqrt(5))

  data.nodes.forEach((node, index) => {
    const angle = index * goldenAngle
    const radius = spread * Math.sqrt((index + 1) / count)
    const jitter = spread * 0.08
    const type = normalizeNodeType(node.type)
    const caption = node.label?.includes('[') ? node.label : nodeCaption(type, node.fullName || node.label || node.id)

    graph.addNode(node.id, {
      x: radius * Math.cos(angle) + (Math.random() - 0.5) * jitter,
      y: radius * Math.sin(angle) + (Math.random() - 0.5) * jitter,
      size: getNodeSize(node),
      color: getNodeColor(node),
      label: caption,
      nodeType: type,
      filePath: node.filePath,
      qualifiedName: node.qualifiedName,
      depth: node.depth,
    })
  })

  data.edges.forEach((edge) => {
    if (!graph.hasNode(edge.source) || !graph.hasNode(edge.target)) return
    const style = EDGE_STYLES[edge.type] || { color: '#4a4a5a', size: 0.8 }
    const key = edge.id || `${edge.source}->${edge.target}:${edge.type}`
    if (graph.hasEdge(key)) return
    graph.addEdgeWithKey(key, edge.source, edge.target, {
      size: style.size,
      color: style.color,
      label: edge.label || edge.type || '',
      relationType: edge.type,
      // arrow shows call / structural direction
      type: 'arrow',
    })
  })

  return graph
}
