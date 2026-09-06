import { http } from './http';
import type {
  PageResponse,
  IncomingSourceRequest,
  IncomingSourceResponse,
} from '../types/api.types';

export const incomingSourcesApi = {
  list: (projectId: string, page = 0, size = 20): Promise<PageResponse<IncomingSourceResponse>> => {
    return http.get<PageResponse<IncomingSourceResponse>>(
      `/api/v1/projects/${projectId}/incoming-sources?page=${page}&size=${size}`
    );
  },

  get: (projectId: string, id: string): Promise<IncomingSourceResponse> => {
    return http.get<IncomingSourceResponse>(
      `/api/v1/projects/${projectId}/incoming-sources/${id}`
    );
  },

  create: (projectId: string, data: IncomingSourceRequest): Promise<IncomingSourceResponse> => {
    return http.post<IncomingSourceResponse>(
      `/api/v1/projects/${projectId}/incoming-sources`,
      data
    );
  },

  update: (projectId: string, id: string, data: IncomingSourceRequest): Promise<IncomingSourceResponse> => {
    return http.put<IncomingSourceResponse>(
      `/api/v1/projects/${projectId}/incoming-sources/${id}`,
      data
    );
  },

  delete: (projectId: string, id: string): Promise<void> => {
    return http.delete<void>(
      `/api/v1/projects/${projectId}/incoming-sources/${id}`
    );
  },
};
