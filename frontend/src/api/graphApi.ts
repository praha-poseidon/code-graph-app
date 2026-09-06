import { apiGet, apiPost } from '../lib/http'
import type { GraphMetadataDto, GraphNodeDto, GraphRelationshipDto, GraphResponseDto } from './graphDto'

interface ApiEnvelope<T> {
  data: T
}

export interface GraphOverviewParams {
  gitRepoUrl?: string
  nodeTypes: string[]
  relationshipTypes: string[]
}

export interface GraphSearchParams {
  keyword: string
  gitRepoUrl?: string
  nodeTypes: string[]
}

export interface GraphTraceBody {
  startNodeId: string
  maxDepth: number
  direction: 'FORWARD' | 'BACKWARD' | 'BOTH'
  gitRepoUrl?: string | null
  nodeTypes: string[]
  relationshipTypes: string[]
}

const appendFilters = (params: URLSearchParams, options: { gitRepoUrl?: string; nodeTypes?: string[]; relationshipTypes?: string[] }) => {
  if (options.gitRepoUrl) params.set('gitRepoUrl', options.gitRepoUrl)
  options.nodeTypes?.forEach(type => params.append('nodeTypes', type))
  options.relationshipTypes?.forEach(type => params.append('relationshipTypes', type))
}

export const graphApi = {
  async importDelta(delta: unknown) {
    await apiPost<ApiEnvelope<null>>('/api/code-graph/delta', delta)
  },

  async metadata() {
    const res = await apiGet<ApiEnvelope<GraphMetadataDto>>('/api/graph/metadata')
    return res.data
  },

  async overview(options: GraphOverviewParams) {
    const params = new URLSearchParams({ limit: '320', relLimit: '1000' })
    appendFilters(params, options)
    const res = await apiGet<ApiEnvelope<GraphResponseDto>>(`/api/graph/overview?${params.toString()}`)
    return res.data
  },

  async search(options: GraphSearchParams) {
    const params = new URLSearchParams({ keyword: options.keyword, limit: '80' })
    appendFilters(params, options)
    const res = await apiGet<ApiEnvelope<{ nodes?: GraphNodeDto[] }>>(`/api/graph/search?${params.toString()}`)
    return res.data?.nodes || []
  },

  async loadNodeGraph(startNode: GraphNodeDto) {
    const startNodeId = startNode.id || startNode.elementId || startNode.qualifiedName || startNode.name || ''
    const [traverseRes, debugRes] = await Promise.all([
      apiPost<ApiEnvelope<{ nodes?: GraphNodeDto[] }>>('/api/graph/traverse', {
        startNodeId,
        maxDepth: 2,
        direction: 'BOTH',
        fetchCode: false,
      }),
      apiGet<ApiEnvelope<{ nodes?: GraphNodeDto[]; relationships?: GraphRelationshipDto[] }>>(
        `/api/graph/debug/node?nodeName=${encodeURIComponent(startNode.name || startNode.qualifiedName || startNodeId)}`,
      ),
    ])

    return {
      nodes: [...(traverseRes.data?.nodes || []), ...(debugRes.data?.nodes || []), startNode],
      relationships: debugRes.data?.relationships || [],
    }
  },

  async trace(body: GraphTraceBody) {
    const res = await apiPost<ApiEnvelope<GraphResponseDto>>('/api/graph/trace', body)
    return res.data
  },

  async query(query: string) {
    const res = await apiPost<ApiEnvelope<GraphResponseDto>>('/api/graph/query', { query })
    return res.data
  },
}
