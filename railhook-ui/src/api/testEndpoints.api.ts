import { http } from './http';

export interface TestEndpointRequest {
  name?: string;
  description?: string;
  ttlHours?: number;
}

export interface TestEndpointResponse {
  id: string;
  projectId: string;
  slug: string;
  url: string;
  name?: string;
  description?: string;
  createdAt: string;
  expiresAt: string;
  requestCount: number;
}

export interface CapturedRequestResponse {
  id: string;
  testEndpointId: string;
  method: string;
  path?: string;
  queryString?: string;
  headers?: string;
  body?: string;
  contentType?: string;
  sourceIp?: string;
  userAgent?: string;
  receivedAt: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export const testEndpointsApi = {
  create: (projectId: string, data?: TestEndpointRequest): Promise<TestEndpointResponse> => {
    return http.post<TestEndpointResponse>(`/api/v1/projects/${projectId}/test-endpoints`, data || {});
  },

  list: (projectId: string): Promise<TestEndpointResponse[]> => {
    return http.get<TestEndpointResponse[]>(`/api/v1/projects/${projectId}/test-endpoints`);
  },

  get: (projectId: string, id: string): Promise<TestEndpointResponse> => {
    return http.get<TestEndpointResponse>(`/api/v1/projects/${projectId}/test-endpoints/${id}`);
  },

  delete: (projectId: string, id: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/test-endpoints/${id}`);
  },

  getRequests: (projectId: string, id: string, page = 0, size = 20): Promise<PageResponse<CapturedRequestResponse>> => {
    return http.get<PageResponse<CapturedRequestResponse>>(
      `/api/v1/projects/${projectId}/test-endpoints/${id}/requests?page=${page}&size=${size}`
    );
  },

  clearRequests: (projectId: string, id: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/test-endpoints/${id}/requests`);
  },
};
