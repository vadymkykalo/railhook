import { http } from './http';

export interface DlqItemResponse {
  deliveryId: string;
  eventId: string;
  endpointId: string;
  subscriptionId: string;
  eventType: string;
  endpointUrl: string;
  attemptCount: number;
  maxAttempts: number;
  lastError: string | null;
  failedAt: string;
  createdAt: string;
}

export interface DlqStatsResponse {
  totalItems: number;
  last24Hours: number;
  last7Days: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface DlqFilters {
  endpointId?: string;
  search?: string;
  dateFrom?: string;
  dateTo?: string;
}

export const dlqApi = {
  list: (projectId: string, page = 0, size = 20, filters?: DlqFilters): Promise<PageResponse<DlqItemResponse>> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters?.endpointId) params.append('endpointId', filters.endpointId);
    if (filters?.search) params.append('search', filters.search);
    if (filters?.dateFrom) params.append('dateFrom', filters.dateFrom);
    if (filters?.dateTo) params.append('dateTo', filters.dateTo);
    return http.get<PageResponse<DlqItemResponse>>(`/api/v1/projects/${projectId}/dlq?${params}`);
  },

  getStats: (projectId: string): Promise<DlqStatsResponse> => {
    return http.get<DlqStatsResponse>(`/api/v1/projects/${projectId}/dlq/stats`);
  },

  getItem: (projectId: string, deliveryId: string): Promise<DlqItemResponse> => {
    return http.get<DlqItemResponse>(`/api/v1/projects/${projectId}/dlq/${deliveryId}`);
  },

  retrySingle: (projectId: string, deliveryId: string): Promise<{ retried: number }> => {
    return http.post<{ retried: number }>(`/api/v1/projects/${projectId}/dlq/${deliveryId}/retry`);
  },

  retryBulk: (projectId: string, deliveryIds: string[]): Promise<{ retried: number; requested: number }> => {
    return http.post<{ retried: number; requested: number }>(`/api/v1/projects/${projectId}/dlq/retry`, { deliveryIds });
  },

  purgeAll: (projectId: string): Promise<{ purged: number }> => {
    return http.delete<{ purged: number }>(`/api/v1/projects/${projectId}/dlq`);
  },
};
