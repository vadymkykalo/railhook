import { http } from './http';
import type {
  TransformationRequest,
  TransformationResponse,
  TransformationVersionDiffResponse,
  TransformationVersionResponse,
} from '../types/api.types';

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

  listVersions: (projectId: string, id: string): Promise<TransformationVersionResponse[]> =>
    http.get<TransformationVersionResponse[]>(`/api/v1/projects/${projectId}/transformations/${id}/versions`),

  getVersion: (projectId: string, id: string, version: number): Promise<TransformationVersionResponse> =>
    http.get<TransformationVersionResponse>(`/api/v1/projects/${projectId}/transformations/${id}/versions/${version}`),

  diffVersions: (projectId: string, id: string, left: number, right: number): Promise<TransformationVersionDiffResponse> =>
    http.get<TransformationVersionDiffResponse>(
      `/api/v1/projects/${projectId}/transformations/${id}/versions/diff?left=${left}&right=${right}`,
    ),

  restoreVersion: (projectId: string, id: string, version: number): Promise<TransformationResponse> =>
    http.post<TransformationResponse>(`/api/v1/projects/${projectId}/transformations/${id}/versions/${version}/restore`),
};
