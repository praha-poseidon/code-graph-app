import type { ElementType } from 'react'
import { Box, Braces, Code2, Folder } from 'lucide-react'

export type WorkbenchMode = 'graph' | 'settings'
export type SettingsTab = 'projects' | 'tasks' | 'workers'

export interface GraphMetadata {
  gitRepoUrls: string[]
  nodeTypes: string[]
  relationshipTypes: string[]
}

export const NODE_FILTER_META: Record<string, { label: string; color: string; icon: ElementType }> = {
  CodePackage: { label: 'Package', color: '#8b5cf6', icon: Folder },
  PACKAGE: { label: 'Package', color: '#8b5cf6', icon: Folder },
  CodeUnit: { label: 'Unit', color: '#f59e0b', icon: Box },
  UNIT: { label: 'Unit', color: '#f59e0b', icon: Box },
  CodeFunction: { label: 'Function', color: '#10b981', icon: Braces },
  FUNCTION: { label: 'Function', color: '#10b981', icon: Braces },
  CodeEndpoint: { label: 'Endpoint', color: '#3b82f6', icon: Code2 },
  ENDPOINT: { label: 'Endpoint', color: '#3b82f6', icon: Code2 },
  HTTP: { label: 'HTTP Endpoint', color: '#3b82f6', icon: Code2 },
}

export const EDGE_FILTER_META: Record<string, { label: string; color: string }> = {
  PACKAGE_TO_UNIT: { label: 'PACKAGE_TO_UNIT', color: '#2d5a3d' },
  UNIT_TO_FUNCTION: { label: 'UNIT_TO_FUNCTION', color: '#0e7490' },
  CALLS: { label: 'CALLS', color: '#8b5cf6' },
  EXTENDS: { label: 'EXTENDS', color: '#ca8a04' },
  IMPLEMENTS: { label: 'IMPLEMENTS', color: '#0891b2' },
  OVERRIDES: { label: 'OVERRIDES', color: '#db2777' },
  ENDPOINT_TO_FUNCTION: { label: 'ENDPOINT_TO_FUNCTION', color: '#2563eb' },
  FUNCTION_TO_ENDPOINT: { label: 'FUNCTION_TO_ENDPOINT', color: '#db2777' },
  MATCHES: { label: 'MATCHES', color: '#f97316' },
}

export const repoDisplayName = (url?: string) => {
  if (!url) return 'All repositories'
  const cleaned = url.replace(/\.git$/, '')
  const parts = cleaned.split(/[/:]/).filter(Boolean)
  return parts.slice(-2).join('/') || cleaned
}
