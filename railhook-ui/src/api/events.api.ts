import { http } from './http';
import type { PageResponse } from '../types/api.types';

export interface SendEventRequest {
  type: string;
  /**
   * The event body, as the caller's own JSON. `unknown` rather than `any`: this is whatever
   * shape the producer sends and nothing here reads into it, so the type's job is to accept any
   * JSON and to stop a field access from compiling — which is exactly what `any` allowed.
   */
  data: unknown;
}

export interface EventResponse {
  id: string;
  projectId: string;
  eventType: string;
  payload: string;
  createdAt: string;
  deliveriesCreated?: number;
  /**
   * Schema-validation errors the event was accepted despite, under a project whose policy is
   * WARN. Only a test-event response carries them; a stored event read back does not.
   */
  schemaWarnings?: string[];
}

export interface EventFilters {
  page?: number;
  size?: number;
  sort?: string;
  eventType?: string;
}

export const eventsApi = {
  listByProject: (projectId: string, filters?: EventFilters): Promise<PageResponse<EventResponse>> => {
    const params = new URLSearchParams();
    
    if (filters?.page !== undefined) params.append('page', filters.page.toString());
    if (filters?.size !== undefined) params.append('size', filters.size.toString());
    if (filters?.sort) params.append('sort', filters.sort);
    if (filters?.eventType) params.append('eventType', filters.eventType);
    
    const queryString = params.toString();
    return http.get<PageResponse<EventResponse>>(
      `/api/v1/projects/${projectId}/events${queryString ? `?${queryString}` : ''}`
    );
  },

  get: (projectId: string, eventId: string): Promise<EventResponse> => {
    return http.get<EventResponse>(`/api/v1/projects/${projectId}/events/${eventId}`);
  },

  sendTestEvent: (projectId: string, payload: SendEventRequest): Promise<EventResponse> => {
    return http.post<EventResponse>(`/api/v1/projects/${projectId}/events/test`, payload);
  },
};
