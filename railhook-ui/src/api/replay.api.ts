import { http } from './http';

export interface ReplayRequest {
  fromDate: string;
  toDate: string;
  eventType?: string;
  endpointId?: string;
  sourceStatus?: string;
}

export interface ReplayEstimateResponse {
  totalEvents: number;
  estimatedDeliveries: number;
  activeSubscriptions: number;
  warning?: string;
}

export interface ReplaySessionResponse {
  id: string;
  projectId: string;
  createdBy: string;
  status: 'PENDING' | 'ESTIMATING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | 'CANCELLING';
  fromDate: string;
  toDate: string;
  eventType?: string;
  endpointId?: string;
  sourceStatus?: string;
  totalEvents: number;
  processedEvents: number;
  deliveriesCreated: number;
  errors: number;
  progressPercent: number;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  cancelledAt?: string;
  createdAt: string;
  durationMs?: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export const replayApi = {
  estimate: (projectId: string, request: ReplayRequest): Promise<ReplayEstimateResponse> => {
    return http.post<ReplayEstimateResponse>(`/api/v1/projects/${projectId}/replay/estimate`, request);
  },

  create: (projectId: string, request: ReplayRequest): Promise<ReplaySessionResponse> => {
    return http.post<ReplaySessionResponse>(`/api/v1/projects/${projectId}/replay`, request);
  },

  get: (projectId: string, sessionId: string): Promise<ReplaySessionResponse> => {
    return http.get<ReplaySessionResponse>(`/api/v1/projects/${projectId}/replay/${sessionId}`);
  },

  list: (projectId: string, page = 0, size = 20): Promise<PageResponse<ReplaySessionResponse>> => {
    return http.get<PageResponse<ReplaySessionResponse>>(
      `/api/v1/projects/${projectId}/replay?page=${page}&size=${size}`
    );
  },

  cancel: (projectId: string, sessionId: string): Promise<ReplaySessionResponse> => {
    return http.post<ReplaySessionResponse>(`/api/v1/projects/${projectId}/replay/${sessionId}/cancel`);
  },
};
