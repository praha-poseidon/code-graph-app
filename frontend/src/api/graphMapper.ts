import type { GraphData, GraphNode } from '../types/graph'
import type { GraphNodeDto, GraphRelationshipDto } from './graphDto'

/** Normalize backend type tags (PACKAGE/UNIT/FUNCTION/HTTP) to workbench kinds. */
export const normalizeNodeType = (raw?: string) => {
  const value = (raw || 'CodeElement').trim()
  const upper = value.toUpperCase()
  if (upper === 'PACKAGE' || upper === 'CODEPACKAGE') return 'CodePackage'
  if (upper === 'UNIT' || upper === 'CODEUNIT' || upper === 'CLASS' || upper === 'INTERFACE') return 'CodeUnit'
  if (upper === 'FUNCTION' || upper === 'CODEFUNCTION' || upper === 'METHOD') return 'CodeFunction'
  if (upper === 'ENDPOINT' || upper === 'CODEENDPOINT' || upper === 'HTTP' || upper === 'MQ' || upper === 'REDIS' || upper === 'DB') {
    return 'CodeEndpoint'
  }
  if (value.startsWith('Code')) return value
  return value
}

export const nodeTypeShortLabel = (type: string) => {
  switch (normalizeNodeType(type)) {
    case 'CodePackage':
      return 'PKG'
    case 'CodeUnit':
      return 'UNIT'
    case 'CodeFunction':
      return 'FN'
    case 'CodeEndpoint':
      return 'EP'
    default:
      return type || 'NODE'
  }
}

export const shortText = (value: string, max = 34) => {
  if (!value) return ''
  const separators = /[.#/$:]/
  const lastPart = value.split(separators).filter(Boolean).pop() || value
  if (lastPart.length <= max) return lastPart
  return `${lastPart.slice(0, Math.max(8, max - 1))}...`
}

export const nodeIdentity = (node: GraphNodeDto) => node.id || node.elementId || node.qualifiedName || node.name || ''

export const nodeDisplayName = (node: GraphNodeDto | GraphNode) => {
  if ('name' in node && node.name) return node.name
  if ('path' in node && node.path) return node.path
  if (node.qualifiedName) {
    const parts = node.qualifiedName.split('.')
    return parts[parts.length - 1] || node.qualifiedName
  }
  if ('label' in node && node.label) return shortText(node.label)
  if ('id' in node && node.id) return shortText(node.id)
  return '(unknown)'
}

/** Visible node caption: type + name */
export const nodeCaption = (type: string, name: string) => {
  const shortName = shortText(name, 28)
  return `[${nodeTypeShortLabel(type)}] ${shortName}`
}

export const mapGraphNode = (node: GraphNodeDto): GraphNode | null => {
  const id = nodeIdentity(node)
  if (!id) return null
  const type = normalizeNodeType(firstLabel(node))
  const name = nodeDisplayName(node)

  return {
    id,
    type,
    label: nodeCaption(type, name),
    fullName: node.name || node.qualifiedName || node.path || id,
    qualifiedName: node.qualifiedName,
    filePath: node.projectFilePath,
    gitRepoUrl: node.gitRepoUrl,
    path: node.path,
    httpMethod: node.httpMethod,
    depth: node.depth,
    properties: node.properties,
  }
}

const firstLabel = (node: GraphNodeDto) => node.type || node.labels?.[0] || 'CodeElement'

export const mapGraphData = (
  nodes: GraphNodeDto[],
  relationships: GraphRelationshipDto[] = [],
): GraphData => {
  const nodeMap = new Map<string, GraphNode>()

  nodes.forEach((node) => {
    const normalized = mapGraphNode(node)
    if (normalized) nodeMap.set(normalized.id, normalized)
  })

  const idByElementId = new Map<string, string>()
  nodes.forEach((node) => {
    const id = nodeIdentity(node)
    if (id && node.elementId) idByElementId.set(node.elementId, id)
  })

  relationships.forEach((rel) => {
    const fromNodeId = idByElementId.get(rel.fromNodeId) || rel.fromNodeId
    const toNodeId = idByElementId.get(rel.toNodeId) || rel.toNodeId
    if (!nodeMap.has(fromNodeId)) {
      nodeMap.set(fromNodeId, {
        id: fromNodeId,
        type: 'CodeElement',
        label: nodeCaption('CodeElement', fromNodeId),
        fullName: fromNodeId,
      })
    }
    if (!nodeMap.has(toNodeId)) {
      const name = rel.toNodeName || rel.toQualifiedName || toNodeId
      nodeMap.set(toNodeId, {
        id: toNodeId,
        type: 'CodeElement',
        label: nodeCaption('CodeElement', name),
        fullName: name,
        qualifiedName: rel.toQualifiedName,
      })
    }
  })

  const edges = relationships.map((rel, index) => {
    const fromNodeId = idByElementId.get(rel.fromNodeId) || rel.fromNodeId
    const toNodeId = idByElementId.get(rel.toNodeId) || rel.toNodeId
    const relType = rel.relationshipType || 'RELATED'
    return {
      id: `${fromNodeId}->${toNodeId}:${relType}:${index}`,
      source: fromNodeId,
      target: toNodeId,
      type: relType,
      label: relType,
      lineNumber: rel.lineNumber,
    }
  })

  return {
    nodes: Array.from(nodeMap.values()),
    edges,
  }
}
