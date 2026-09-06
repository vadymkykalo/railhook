import { http } from './http';
import type { DeliveryResponse, DeliveryAttemptResponse, PageResponse } from '../types/api.types';

export interface DeliveryFilters {
  page?: number;
  size?: number;
  sort?: string;
  status?: string;
  endpointId?: string;
  eventId?: string;
  eventType?: string;
  fromDate?: string;
  toDate?: string;
}

export interface BulkReplayRequest {
  deliveryIds?: string[];
  status?: string;
  endpointId?: string;
  projectId?: string;
  limit?: number;
}

export interface BulkReplayResponse {
  totalRequested: number;
  replayed: number;
  skipped: number;
  totalMatched: number;
  hasMore: boolean;
  message: string;
}

export interface DryRunReplayResponse {
  deliveryId: string;
  eventId: string;
  endpointId: string;
  endpointUrl: string;
  eventType: string;
  idempotencyKey: string;
  payload: string;
  previousAttemptCount: number;
  maxAttempts: number;
  currentStatus: string;
  lastAttemptAt?: string;
  previousAttempts: { attemptNumber: number; httpStatusCode?: number; errorMessage?: string; durationMs?: number; createdAt: string }[];
  plan: string;
}

export const deliveriesApi = {
  listByProject: (projectId: string, filters?: DeliveryFilters): Promise<PageResponse<DeliveryResponse>> => {
    const params = new URLSearchParams();
    
    if (filters?.page !== undefined) params.append('page', filters.page.toString());
    if (filters?.size !== undefined) params.append('size', filters.size.toString());
    if (filters?.status) params.append('status', filters.status);
    if (filters?.endpointId) params.append('endpointId', filters.endpointId);
    if (filters?.eventId) params.append('eventId', filters.eventId);
    if (filters?.eventType) params.append('eventType', filters.eventType);
    if (filters?.sort) params.append('sort', filters.sort);
    if (filters?.fromDate) params.append('fromDate', filters.fromDate);
    if (filters?.toDate) params.append('toDate', filters.toDate);
    
    const queryString = params.toString();
    return http.get<PageResponse<DeliveryResponse>>(
      `/api/v1/deliveries/projects/${projectId}${queryString ? `?${queryString}` : ''}`
    );
  },

  get: (id: string): Promise<DeliveryResponse> => {
    return http.get<DeliveryResponse>(`/api/v1/deliveries/${id}`);
  },

  replay: (id: string): Promise<void> => {
    return http.post<void>(`/api/v1/deliveries/${id}/replay`);
  },

  dryRunReplay: (id: string): Promise<DryRunReplayResponse> => {
    return http.post<DryRunReplayResponse>(`/api/v1/deliveries/${id}/replay?dryRun=true`);
  },

  replayFromAttempt: (id: string, fromAttempt: number): Promise<void> => {
    return http.post<void>(`/api/v1/deliveries/${id}/replay?fromAttempt=${fromAttempt}`);
  },

  bulkReplay: (request: BulkReplayRequest): Promise<BulkReplayResponse> => {
    return http.post<BulkReplayResponse>('/api/v1/deliveries/bulk-replay', request);
  },

  getAttempts: (deliveryId: string): Promise<DeliveryAttemptResponse[]> => {
    return http.get<DeliveryAttemptResponse[]>(`/api/v1/deliveries/${deliveryId}/attempts`);
  },
};
