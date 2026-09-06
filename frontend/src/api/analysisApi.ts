import { apiGet, apiPost } from '../lib/http'

export type AnalysisTaskStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELED'
export type AnalysisTaskEventStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | 'CANCELED' | 'RETRYING'
export type AnalysisWorkerStatus = 'IDLE' | 'WORKING' | 'OFFLINE'

export interface AnalysisTask {
  id: string
  repositoryId: number
  status: AnalysisTaskStatus
  progressCurrent: number
  progressTotal: number
  message?: string | null
  errorDetails?: string | null
  attemptCount: number
  maxAttempts: number
  leaseOwner?: string | null
  leaseUntil?: string | null
  heartbeatAt?: string | null
  nextAttemptAt?: string | null
  cancelRequested: boolean
  createdAt: string
  startedAt?: string | null
  finishedAt?: string | null
  updatedAt: string
}

export interface AnalysisTaskEvent {
  id: string
  taskId: string
  stage: string
  status: AnalysisTaskEventStatus
  message?: string | null
  details?: string | null
  startedAt: string
  finishedAt?: string | null
}

export interface AnalysisWorker {
  workerId: string
  hostName: string
  processId: number
  status: AnalysisWorkerStatus
  activeTaskId?: string | null
  startedAt: string
  heartbeatAt: string
  stoppedAt?: string | null
  lastError?: string | null
}

export interface RepositoryOption {
  id: number
  name: string
  gitRepoUrl: string
}

interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

const unwrap = <T>(response: ApiResponse<T>): T => {
  if (response.code !== 200) throw new Error(response.message)
  return response.data
}

export const fetchTasks = async (repositoryId?: number | null) => {
  const query = repositoryId == null ? '' : `?repositoryId=${repositoryId}`
  return unwrap(await apiGet<ApiResponse<AnalysisTask[]>>(`/api/tasks${query}`))
}

export const fetchTaskEvents = async (taskId: string) =>
  unwrap(await apiGet<ApiResponse<AnalysisTaskEvent[]>>(`/api/tasks/${taskId}/events`))

export const fetchWorkers = async () =>
  unwrap(await apiGet<ApiResponse<AnalysisWorker[]>>('/api/workers'))

export const fetchRepositoryOptions = async () =>
  unwrap(await apiGet<ApiResponse<RepositoryOption[]>>('/api/config/projects'))

export const cancelAnalysisTask = async (taskId: string) =>
  unwrap(await apiPost<ApiResponse<AnalysisTask>>(`/api/tasks/${taskId}/cancel`))
