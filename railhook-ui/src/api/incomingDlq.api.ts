import { http } from './http';
import type { DlqStatsResponse, IncomingDlqItemResponse, PageResponse } from '../types/api.types';

export interface IncomingDlqFilters {
  destinationId?: string;
}

export const incomingDlqApi = {
  list: (
    projectId: string,
    page = 0,
    size = 20,
    filters?: IncomingDlqFilters,
  ): Promise<PageResponse<IncomingDlqItemResponse>> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters?.destinationId) params.append('destinationId', filters.destinationId);
    return http.get<PageResponse<IncomingDlqItemResponse>>(
      `/api/v1/projects/${projectId}/incoming-dlq?${params}`,
    );
  },

  getStats: (projectId: string): Promise<DlqStatsResponse> =>
    http.get<DlqStatsResponse>(`/api/v1/projects/${projectId}/incoming-dlq/stats`),

  getItem: (projectId: string, forwardAttemptId: string): Promise<IncomingDlqItemResponse> =>
    http.get<IncomingDlqItemResponse>(`/api/v1/projects/${projectId}/incoming-dlq/${forwardAttemptId}`),

  retrySingle: (projectId: string, forwardAttemptId: string): Promise<{ retried: number }> =>
    http.post<{ retried: number }>(`/api/v1/projects/${projectId}/incoming-dlq/${forwardAttemptId}/retry`),

  retryBulk: (
    projectId: string,
    forwardAttemptIds: string[],
  ): Promise<{ retried: number; requested: number }> =>
    http.post<{ retried: number; requested: number }>(
      `/api/v1/projects/${projectId}/incoming-dlq/retry`,
      { forwardAttemptIds },
    ),

  purgeAll: (projectId: string): Promise<{ purged: number }> =>
    http.delete<{ purged: number }>(`/api/v1/projects/${projectId}/incoming-dlq`),
};
