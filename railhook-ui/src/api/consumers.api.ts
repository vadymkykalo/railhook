import { http } from './http';
import type {
  ConsumerRequest, ConsumerResponse, EndpointResponse, PageResponse,
  PortalSessionRequest, PortalSessionResponse,
} from '../types/api.types';

export const consumersApi = {
  listPaged: (projectId: string, page = 0, size = 20): Promise<PageResponse<ConsumerResponse>> => {
    return http.get<PageResponse<ConsumerResponse>>(
      `/api/v1/projects/${projectId}/consumers?page=${page}&size=${size}`
    );
  },

  create: (projectId: string, data: ConsumerRequest): Promise<ConsumerResponse> => {
    return http.post<ConsumerResponse>(`/api/v1/projects/${projectId}/consumers`, data);
  },

  update: (projectId: string, id: string, data: ConsumerRequest): Promise<ConsumerResponse> => {
    return http.put<ConsumerResponse>(`/api/v1/projects/${projectId}/consumers/${id}`, data);
  },

  delete: (projectId: string, id: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/consumers/${id}`);
  },

  listEndpoints: (projectId: string, id: string): Promise<EndpointResponse[]> => {
    return http.get<EndpointResponse[]>(`/api/v1/projects/${projectId}/consumers/${id}/endpoints`);
  },

  createPortalSession: (
    projectId: string, id: string, data: PortalSessionRequest = {},
  ): Promise<PortalSessionResponse> => {
    return http.post<PortalSessionResponse>(`/api/v1/projects/${projectId}/consumers/${id}/portal-sessions`, data);
  },

  revokePortalSessions: (projectId: string, id: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/consumers/${id}/portal-sessions`);
  },
};
