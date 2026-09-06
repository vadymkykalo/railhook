import { http } from './http';

// ─── Types ──────────────────────────────────────────────────────

export type TriggerType = 'WEBHOOK_EVENT' | 'MANUAL' | 'SCHEDULE';
export type ExecutionStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
export type StepStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';

export interface WorkflowNodeData {
  label?: string;
  [key: string]: unknown;
}

export interface WorkflowNode {
  id: string;
  type: string;
  position: { x: number; y: number };
  data: WorkflowNodeData;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
}

export interface WorkflowDefinition {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

export interface WorkflowRequest {
  name: string;
  description?: string;
  enabled?: boolean;
  definition?: WorkflowDefinition;
  triggerType?: TriggerType;
  triggerConfig?: Record<string, unknown>;
}

export interface WorkflowResponse {
  id: string;
  projectId: string;
  name: string;
  description: string | null;
  enabled: boolean;
  definition: WorkflowDefinition;
  triggerType: TriggerType;
  triggerConfig: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
  totalExecutions: number;
  successfulExecutions: number;
  failedExecutions: number;
}

export interface StepExecutionResponse {
  id: string;
  nodeId: string;
  nodeType: string;
  status: StepStatus;
  inputData: unknown;
  outputData: unknown;
  errorMessage: string | null;
  attemptCount: number;
  durationMs: number | null;
  startedAt: string | null;
  completedAt: string | null;
}

export interface WorkflowExecutionResponse {
  id: string;
  workflowId: string;
  triggerEventId: string | null;
  status: ExecutionStatus;
  triggerData: unknown;
  startedAt: string;
  completedAt: string | null;
  errorMessage: string | null;
  durationMs: number | null;
  steps: StepExecutionResponse[] | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// ─── API ────────────────────────────────────────────────────────

export const workflowsApi = {
  list: (projectId: string): Promise<WorkflowResponse[]> =>
    http.get<WorkflowResponse[]>(`/api/v1/projects/${projectId}/workflows`),

  get: (projectId: string, id: string): Promise<WorkflowResponse> =>
    http.get<WorkflowResponse>(`/api/v1/projects/${projectId}/workflows/${id}`),

  create: (projectId: string, data: WorkflowRequest): Promise<WorkflowResponse> =>
    http.post<WorkflowResponse>(`/api/v1/projects/${projectId}/workflows`, data),

  update: (projectId: string, id: string, data: WorkflowRequest): Promise<WorkflowResponse> =>
    http.put<WorkflowResponse>(`/api/v1/projects/${projectId}/workflows/${id}`, data),

  delete: (projectId: string, id: string): Promise<void> =>
    http.delete<void>(`/api/v1/projects/${projectId}/workflows/${id}`),

  toggle: (projectId: string, id: string, enabled: boolean): Promise<WorkflowResponse> =>
    http.patch<WorkflowResponse>(`/api/v1/projects/${projectId}/workflows/${id}/toggle`, { enabled }),

  listExecutions: (projectId: string, workflowId: string, page = 0, size = 20): Promise<PageResponse<WorkflowExecutionResponse>> =>
    http.get<PageResponse<WorkflowExecutionResponse>>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/executions?page=${page}&size=${size}`
    ),

  getExecution: (projectId: string, workflowId: string, executionId: string): Promise<WorkflowExecutionResponse> =>
    http.get<WorkflowExecutionResponse>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/executions/${executionId}`
    ),

  trigger: (projectId: string, workflowId: string, testPayload?: Record<string, unknown>): Promise<WorkflowExecutionResponse> =>
    http.post<WorkflowExecutionResponse>(
      `/api/v1/projects/${projectId}/workflows/${workflowId}/trigger`,
      testPayload ?? {}
    ),
};
