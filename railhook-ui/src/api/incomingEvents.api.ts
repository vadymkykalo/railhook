import { http } from './http';
import type {
  PageResponse,
  IncomingEventResponse,
  IncomingForwardAttemptResponse,
  ReplayEventResponse,
  IncomingBulkReplayRequest,
  IncomingBulkReplayResponse,
} from '../types/api.types';

export interface IncomingEventFilters {
  sourceId?: string;
  page?: number;
  size?: number;
}

export const incomingEventsApi = {
  list: (projectId: string, filters?: IncomingEventFilters): Promise<PageResponse<IncomingEventResponse>> => {
    const params = new URLSearchParams();
    if (filters?.sourceId) params.append('sourceId', filters.sourceId);
    if (filters?.page !== undefined) params.append('page', filters.page.toString());
    if (filters?.size !== undefined) params.append('size', filters.size.toString());
    params.append('sort', 'receivedAt,desc');
    const qs = params.toString();
    return http.get<PageResponse<IncomingEventResponse>>(
      `/api/v1/projects/${projectId}/incoming-events${qs ? `?${qs}` : ''}`
    );
  },

  get: (projectId: string, id: string): Promise<IncomingEventResponse> => {
    return http.get<IncomingEventResponse>(
      `/api/v1/projects/${projectId}/incoming-events/${id}`
    );
  },

  getAttempts: (projectId: string, eventId: string, page = 0, size = 20): Promise<PageResponse<IncomingForwardAttemptResponse>> => {
    return http.get<PageResponse<IncomingForwardAttemptResponse>>(
      `/api/v1/projects/${projectId}/incoming-events/${eventId}/attempts?page=${page}&size=${size}`
    );
  },

  replay: (projectId: string, eventId: string): Promise<ReplayEventResponse> => {
    return http.post<ReplayEventResponse>(
      `/api/v1/projects/${projectId}/incoming-events/${eventId}/replay`
    );
  },

  bulkReplay: (projectId: string, request: IncomingBulkReplayRequest): Promise<IncomingBulkReplayResponse> => {
    return http.post<IncomingBulkReplayResponse>(
      `/api/v1/projects/${projectId}/incoming-events/bulk-replay`,
      request
    );
  },
};
