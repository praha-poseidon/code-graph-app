export interface GraphNodeDto {
  properties?: Record<string, unknown>
  id?: string
  elementId?: string
  type?: string
  labels?: string[]
  name?: string
  qualifiedName?: string
  projectFilePath?: string
  gitRepoUrl?: string
  path?: string
  httpMethod?: string
  depth?: number
}

export interface GraphRelationshipDto {
  fromNodeId: string
  toNodeId: string
  relationshipType: string
  toNodeName?: string
  toQualifiedName?: string
  lineNumber?: number
}

export interface GraphResponseDto {
  nodes?: GraphNodeDto[]
  relationships?: GraphRelationshipDto[]
}

export interface GraphMetadataDto {
  gitRepoUrls: string[]
  nodeTypes: string[]
  relationshipTypes: string[]
}
