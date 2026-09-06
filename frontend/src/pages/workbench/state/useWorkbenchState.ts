import { useEffect, useRef, useState, type Dispatch, type PointerEvent as ReactPointerEvent, type SetStateAction } from 'react'
import { graphApi } from '../../../api/graphApi'
import type { GraphNodeDto } from '../../../api/graphDto'
import { mapGraphData, nodeIdentity } from '../../../api/graphMapper'
import type { GraphData, GraphNode } from '../../../types/graph'
import type { GraphMetadata, SettingsTab, WorkbenchMode } from '../workbench-constants'

function mergeGraphData(base: GraphData, addition: GraphData): GraphData {
  const nodes = new Map<string, GraphNode>()
  const edges = new Map<string, GraphData['edges'][number]>()

  base.nodes.forEach(node => nodes.set(node.id, node))
  addition.nodes.forEach(node => nodes.set(node.id, { ...nodes.get(node.id), ...node }))
  base.edges.forEach(edge => edges.set(edge.id, edge))
  addition.edges.forEach(edge => edges.set(edge.id, edge))

  return {
    nodes: Array.from(nodes.values()),
    edges: Array.from(edges.values()),
  }
}

export interface WorkbenchController {
  mode: WorkbenchMode
  setMode: Dispatch<SetStateAction<WorkbenchMode>>
  settingsTab: SettingsTab
  setSettingsTab: Dispatch<SetStateAction<SettingsTab>>
  query: string
  setQuery: Dispatch<SetStateAction<string>>
  searching: boolean
  results: GraphNodeDto[]
  graphData: GraphData
  selectedNode: GraphNode | null
  setSelectedNode: Dispatch<SetStateAction<GraphNode | null>>
  traceRootNode: GraphNode | null
  loadingGraph: boolean
  error: string | null
  metadata: GraphMetadata
  filtersReady: boolean
  selectedGitRepoUrl: string
  setSelectedGitRepoUrl: Dispatch<SetStateAction<string>>
  selectedNodeTypes: string[]
  selectedRelationshipTypes: string[]
  queryOpen: boolean
  setQueryOpen: Dispatch<SetStateAction<boolean>>
  cypher: string
  setCypher: Dispatch<SetStateAction<string>>
  querying: boolean
  traceDepth: number
  setTraceDepth: Dispatch<SetStateAction<number>>
  traceDirection: 'FORWARD' | 'BACKWARD' | 'BOTH'
  setTraceDirection: Dispatch<SetStateAction<'FORWARD' | 'BACKWARD' | 'BOTH'>>
  filterOpen: boolean
  setFilterOpen: Dispatch<SetStateAction<boolean>>
  insightOpen: boolean
  setInsightOpen: Dispatch<SetStateAction<boolean>>
  leftPanelOpen: boolean
  setLeftPanelOpen: Dispatch<SetStateAction<boolean>>
  aiOpen: boolean
  setAiOpen: Dispatch<SetStateAction<boolean>>
  aiWidth: number
  importingDelta: boolean
  beginAiResize: (event: ReactPointerEvent<HTMLDivElement>) => void
  importGraphDeltaFile: (file: File) => Promise<void>
  loadNodeGraph: (node: GraphNodeDto) => void
  startTraceFromNode: (node: GraphNode) => void
  runCypherQuery: () => Promise<void>
  toggleNodeType: (type: string) => void
  toggleValue: (value: string, values: string[], setValues: Dispatch<SetStateAction<string[]>>) => void
  changeProject: Dispatch<SetStateAction<string>>
  selectedNodeTypesSetter: Dispatch<SetStateAction<string[]>>
  selectedRelationshipTypesSetter: Dispatch<SetStateAction<string[]>>
}

export function useWorkbenchState(): WorkbenchController {
  const [mode, setMode] = useState<WorkbenchMode>('graph')
  const [settingsTab, setSettingsTab] = useState<SettingsTab>('projects')
  const [query, setQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [results, setResults] = useState<GraphNodeDto[]>([])
  const [graphData, setGraphData] = useState<GraphData>({ nodes: [], edges: [] })
  const [selectedNode, setSelectedNode] = useState<GraphNode | null>(null)
  const [traceRootNode, setTraceRootNode] = useState<GraphNode | null>(null)
  const [loadingGraph, setLoadingGraph] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [metadata, setMetadata] = useState<GraphMetadata>({ gitRepoUrls: [], nodeTypes: [], relationshipTypes: [] })
  const [filtersReady, setFiltersReady] = useState(false)
  const [selectedGitRepoUrl, setSelectedGitRepoUrl] = useState('')
  const [selectedNodeTypes, setSelectedNodeTypes] = useState<string[]>([])
  const [selectedRelationshipTypes, setSelectedRelationshipTypes] = useState<string[]>([])
  const [queryOpen, setQueryOpen] = useState(false)
  const [cypher, setCypher] = useState('MATCH p=(n)-[r]->(m) RETURN p LIMIT 100')
  const [querying, setQuerying] = useState(false)
  const [traceDepth, setTraceDepth] = useState(2)
  const [traceDirection, setTraceDirection] = useState<'FORWARD' | 'BACKWARD' | 'BOTH'>('FORWARD')
  const [filterOpen, setFilterOpen] = useState(false)
  const [insightOpen, setInsightOpen] = useState(false)
  const [leftPanelOpen, setLeftPanelOpen] = useState(false)
  const [aiOpen, setAiOpen] = useState(false)
  const [aiWidth, setAiWidth] = useState(440)
  const [importingDelta, setImportingDelta] = useState(false)
  const aiResizeRef = useRef<{ startX: number; startWidth: number } | null>(null)

  const beginAiResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.stopPropagation()

    aiResizeRef.current = {
      startX: event.clientX,
      startWidth: aiWidth,
    }

    const handleMove = (moveEvent: PointerEvent) => {
      const resize = aiResizeRef.current
      if (!resize) return
      const maxWidth = Math.max(320, Math.min(720, window.innerWidth - 120))
      const nextWidth = resize.startWidth + (resize.startX - moveEvent.clientX)
      setAiWidth(Math.min(maxWidth, Math.max(320, nextWidth)))
    }

    const handleUp = () => {
      aiResizeRef.current = null
      window.removeEventListener('pointermove', handleMove)
      window.removeEventListener('pointerup', handleUp)
    }

    window.addEventListener('pointermove', handleMove)
    window.addEventListener('pointerup', handleUp)
  }

  const loadOverview = async () => {
    setLoadingGraph(true)
    setError(null)
    try {
      const res = await graphApi.overview({
        gitRepoUrl: selectedGitRepoUrl,
        nodeTypes: selectedNodeTypes,
        relationshipTypes: selectedRelationshipTypes,
      })
      setGraphData(mapGraphData(res?.nodes || [], res?.relationships || []))
      setSelectedNode(null)
      setTraceRootNode(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载图谱概览失败')
    } finally {
      setLoadingGraph(false)
    }
  }

  useEffect(() => {
    graphApi.metadata()
      .then(data => {
        data = data || { gitRepoUrls: [], nodeTypes: [], relationshipTypes: [] }
        setMetadata(data)
        setSelectedNodeTypes(data.nodeTypes || [])
        setSelectedRelationshipTypes(data.relationshipTypes || [])
        setFiltersReady(true)
      })
      .catch(() => {
        setMetadata({ gitRepoUrls: [], nodeTypes: ['CodeEndpoint', 'CodeFunction', 'CodeUnit', 'CodePackage'], relationshipTypes: [] })
        setSelectedNodeTypes(['CodeEndpoint', 'CodeFunction', 'CodeUnit', 'CodePackage'])
        setSelectedRelationshipTypes([])
        setFiltersReady(true)
      })
  }, [])

  useEffect(() => {
    if (!filtersReady || traceRootNode) return
    const timer = window.setTimeout(() => {
      loadOverview()
    }, 250)
    return () => window.clearTimeout(timer)
  }, [filtersReady, selectedGitRepoUrl, selectedNodeTypes, selectedRelationshipTypes, traceRootNode?.id])

  useEffect(() => {
    const keyword = query.trim()
    if (!keyword) {
      setResults([])
      return
    }

    const timer = window.setTimeout(async () => {
      setSearching(true)
      setError(null)
      try {
        const nodes = await graphApi.search({ keyword, gitRepoUrl: selectedGitRepoUrl, nodeTypes: selectedNodeTypes })
        setResults(nodes)
      } catch (err) {
        setResults([])
        setError(err instanceof Error ? err.message : '搜索失败')
      } finally {
        setSearching(false)
      }
    }, 300)

    return () => window.clearTimeout(timer)
  }, [query, selectedGitRepoUrl, selectedNodeTypes])

  const loadNodeGraph = async (node: GraphNodeDto) => {
    const startNodeId = nodeIdentity(node)
    if (!startNodeId) return

    setLoadingGraph(true)
    setError(null)
    try {
      const res = await graphApi.loadNodeGraph(node)
      const nextGraph = mapGraphData(res.nodes || [], res.relationships || [])
      const rootNode = nextGraph.nodes.find((item) => item.id === startNodeId) || null
      setGraphData(nextGraph)
      setTraceRootNode(rootNode)
      setSelectedNode(rootNode)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载图谱失败')
    } finally {
      setLoadingGraph(false)
    }
  }

  const traceSelectedNode = async (node: GraphNode, append = false) => {
    if (loadingGraph) return
    setLoadingGraph(true)
    setError(null)
    try {
      const res = await graphApi.trace({
        startNodeId: node.id,
        maxDepth: traceDepth,
        direction: traceDirection,
        gitRepoUrl: selectedGitRepoUrl || null,
        nodeTypes: selectedNodeTypes,
        relationshipTypes: selectedRelationshipTypes,
      })
      const nextGraph = mapGraphData(res?.nodes || [], res?.relationships || [])
      const visibleGraph = append ? mergeGraphData(graphData, nextGraph) : nextGraph
      setGraphData(visibleGraph)
      const nextRoot = append
        ? traceRootNode
        : visibleGraph.nodes.find(item => item.id === node.id) || node
      if (nextRoot) setTraceRootNode(nextRoot)
      setSelectedNode(current => {
        if (!current) return current
        return visibleGraph.nodes.find(item => item.id === current.id) || current
      })
    } catch (err) {
      setError(err instanceof Error ? err.message : '节点追踪失败')
    } finally {
      setLoadingGraph(false)
    }
  }

  useEffect(() => {
    if (!traceRootNode) return
    const timer = window.setTimeout(() => {
      traceSelectedNode(traceRootNode)
    }, 250)
    return () => window.clearTimeout(timer)
  }, [traceRootNode?.id, traceDepth, traceDirection, selectedGitRepoUrl, selectedNodeTypes, selectedRelationshipTypes])

  const startTraceFromNode = (node: GraphNode) => {
    setSelectedNode(node)
    if (traceRootNode?.id === node.id) {
      traceSelectedNode(node)
      return
    }
    if (traceRootNode) {
      traceSelectedNode(node, true)
      return
    }
    setTraceRootNode(node)
  }

  const toggleValue = (value: string, values: string[], setValues: Dispatch<SetStateAction<string[]>>) => {
    setValues(values.includes(value) ? values.filter(item => item !== value) : [...values, value])
  }

  const toggleNodeType = (type: string) => {
    toggleValue(type, selectedNodeTypes, setSelectedNodeTypes)
  }

  const runCypherQuery = async () => {
    if (!cypher.trim() || querying) return
    setQuerying(true)
    setError(null)
    try {
      const res = await graphApi.query(cypher)
      setGraphData(mapGraphData(res?.nodes || [], res?.relationships || []))
      setSelectedNode(null)
      setTraceRootNode(null)
      setQueryOpen(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : '查询失败')
    } finally {
      setQuerying(false)
    }
  }

  const importGraphDeltaFile = async (file: File) => {
    if (importingDelta) return
    setImportingDelta(true)
    setLoadingGraph(true)
    setError(null)
    try {
      const text = await file.text()
      const delta = JSON.parse(text) as { scope?: { gitRepoUrl?: string } }
      await graphApi.importDelta(delta)

      const nextMetadata = await graphApi.metadata()
      const nextNodeTypes = nextMetadata?.nodeTypes || []
      const nextRelationshipTypes = nextMetadata?.relationshipTypes || []
      const nextGitRepoUrl = delta.scope?.gitRepoUrl || ''

      setMetadata(nextMetadata || { gitRepoUrls: [], nodeTypes: [], relationshipTypes: [] })
      setSelectedGitRepoUrl(nextGitRepoUrl)
      setSelectedNodeTypes(nextNodeTypes)
      setSelectedRelationshipTypes(nextRelationshipTypes)
      setFiltersReady(true)

      const res = await graphApi.overview({
        gitRepoUrl: nextGitRepoUrl,
        nodeTypes: nextNodeTypes,
        relationshipTypes: nextRelationshipTypes,
      })
      setGraphData(mapGraphData(res?.nodes || [], res?.relationships || []))
      setSelectedNode(null)
      setTraceRootNode(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : '导入 GraphDelta 失败')
    } finally {
      setImportingDelta(false)
      setLoadingGraph(false)
    }
  }

  return {
    mode,
    setMode,
    settingsTab,
    setSettingsTab,
    query,
    setQuery,
    searching,
    results,
    graphData,
    selectedNode,
    setSelectedNode,
    traceRootNode,
    loadingGraph,
    error,
    metadata,
    filtersReady,
    selectedGitRepoUrl,
    setSelectedGitRepoUrl,
    selectedNodeTypes,
    selectedRelationshipTypes,
    queryOpen,
    setQueryOpen,
    cypher,
    setCypher,
    querying,
    traceDepth,
    setTraceDepth,
    traceDirection,
    setTraceDirection,
    filterOpen,
    setFilterOpen,
    insightOpen,
    setInsightOpen,
    leftPanelOpen,
    setLeftPanelOpen,
    aiOpen,
    setAiOpen,
    aiWidth,
    importingDelta,
    beginAiResize,
    importGraphDeltaFile,
    loadNodeGraph,
    startTraceFromNode,
    runCypherQuery,
    toggleNodeType,
    toggleValue,
    changeProject: setSelectedGitRepoUrl,
    selectedNodeTypesSetter: setSelectedNodeTypes,
    selectedRelationshipTypesSetter: setSelectedRelationshipTypes,
  }
}
