export interface GraphNode {
  properties?: Record<string, unknown>
  id: string
  type: string
  label: string
  fullName?: string
  qualifiedName?: string
  filePath?: string
  gitRepoUrl?: string
  path?: string
  httpMethod?: string
  depth?: number
}

export interface GraphEdge {
  id: string
  source: string
  target: string
  type: string
  /** Edge type label rendered on canvas */
  label?: string
  lineNumber?: number
}

export interface GraphData {
  nodes: GraphNode[]
  edges: GraphEdge[]
}
