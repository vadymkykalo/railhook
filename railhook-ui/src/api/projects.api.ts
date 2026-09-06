import { http } from './http';
import type { ProjectRequest, ProjectResponse } from '../types/api.types';

export const projectsApi = {
  list: (): Promise<ProjectResponse[]> => {
    return http.get<ProjectResponse[]>('/api/v1/projects');
  },

  create: (data: ProjectRequest): Promise<ProjectResponse> => {
    return http.post<ProjectResponse>('/api/v1/projects', data);
  },

  get: (id: string): Promise<ProjectResponse> => {
    return http.get<ProjectResponse>(`/api/v1/projects/${id}`);
  },

  update: (id: string, data: ProjectRequest): Promise<ProjectResponse> => {
    return http.put<ProjectResponse>(`/api/v1/projects/${id}`, data);
  },

  delete: (id: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${id}`);
  },
};
