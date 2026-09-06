import { http } from './http';
import type {
  PageResponse,
  IncomingDestinationRequest,
  IncomingDestinationResponse,
} from '../types/api.types';

export const incomingDestinationsApi = {
  list: (projectId: string, sourceId: string, page = 0, size = 20): Promise<PageResponse<IncomingDestinationResponse>> => {
    return http.get<PageResponse<IncomingDestinationResponse>>(
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations?page=${page}&size=${size}`
    );
  },

  get: (projectId: string, sourceId: string, id: string): Promise<IncomingDestinationResponse> => {
    return http.get<IncomingDestinationResponse>(
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${id}`
    );
  },

  create: (projectId: string, sourceId: string, data: IncomingDestinationRequest): Promise<IncomingDestinationResponse> => {
    return http.post<IncomingDestinationResponse>(
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations`,
      data
    );
  },

  update: (projectId: string, sourceId: string, id: string, data: IncomingDestinationRequest): Promise<IncomingDestinationResponse> => {
    return http.put<IncomingDestinationResponse>(
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${id}`,
      data
    );
  },

  delete: (projectId: string, sourceId: string, id: string): Promise<void> => {
    return http.delete<void>(
      `/api/v1/projects/${projectId}/incoming-sources/${sourceId}/destinations/${id}`
    );
  },
};
