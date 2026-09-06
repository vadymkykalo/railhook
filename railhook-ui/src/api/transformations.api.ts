import { http } from './http';
import type { TransformationRequest, TransformationResponse } from '../types/api.types';

export const transformationsApi = {
  list: (projectId: string): Promise<TransformationResponse[]> =>
    http.get<TransformationResponse[]>(`/api/v1/projects/${projectId}/transformations`),

  get: (projectId: string, id: string): Promise<TransformationResponse> =>
    http.get<TransformationResponse>(`/api/v1/projects/${projectId}/transformations/${id}`),

  create: (projectId: string, data: TransformationRequest): Promise<TransformationResponse> =>
    http.post<TransformationResponse>(`/api/v1/projects/${projectId}/transformations`, data),

  update: (projectId: string, id: string, data: TransformationRequest): Promise<TransformationResponse> =>
    http.put<TransformationResponse>(`/api/v1/projects/${projectId}/transformations/${id}`, data),

  delete: (projectId: string, id: string): Promise<void> =>
    http.delete<void>(`/api/v1/projects/${projectId}/transformations/${id}`),
};
