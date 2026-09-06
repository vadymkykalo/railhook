import { http } from './http';
import type { EndpointRequest, EndpointResponse, PageResponse } from '../types/api.types';

export interface EndpointTestResponse {
  success: boolean;
  httpStatusCode?: number;
  responseBody?: string;
  errorMessage?: string;
  latencyMs: number;
  message: string;
}

export const endpointsApi = {
  list: (projectId: string): Promise<EndpointResponse[]> => {
    return http.get<PageResponse<EndpointResponse>>(`/api/v1/projects/${projectId}/endpoints?size=1000`)
      .then(page => page.content);
  },

  listPaged: (projectId: string, page = 0, size = 20): Promise<PageResponse<EndpointResponse>> => {
    return http.get<PageResponse<EndpointResponse>>(`/api/v1/projects/${projectId}/endpoints?page=${page}&size=${size}`);
  },

  create: (projectId: string, data: EndpointRequest): Promise<EndpointResponse> => {
    return http.post<EndpointResponse>(`/api/v1/projects/${projectId}/endpoints`, data);
  },

  get: (projectId: string, id: string): Promise<EndpointResponse> => {
    return http.get<EndpointResponse>(`/api/v1/projects/${projectId}/endpoints/${id}`);
  },

  update: (projectId: string, id: string, data: EndpointRequest): Promise<EndpointResponse> => {
    return http.put<EndpointResponse>(`/api/v1/projects/${projectId}/endpoints/${id}`, data);
  },

  delete: (projectId: string, id: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/endpoints/${id}`);
  },

  rotateSecret: (projectId: string, id: string): Promise<EndpointResponse> => {
    return http.post<EndpointResponse>(`/api/v1/projects/${projectId}/endpoints/${id}/rotate-secret`);
  },

  test: (projectId: string, id: string): Promise<EndpointTestResponse> => {
    return http.post<EndpointTestResponse>(`/api/v1/projects/${projectId}/endpoints/${id}/test`);
  },

  configureMtls: (projectId: string, id: string, data: MtlsConfigRequest): Promise<EndpointResponse> => {
    return http.post<EndpointResponse>(`/api/v1/projects/${projectId}/endpoints/${id}/mtls`, data);
  },

  disableMtls: (projectId: string, id: string): Promise<EndpointResponse> => {
    return http.delete<EndpointResponse>(`/api/v1/projects/${projectId}/endpoints/${id}/mtls`);
  },

  verify: (projectId: string, id: string): Promise<VerificationResponse> => {
    return http.post<VerificationResponse>(`/api/v1/projects/${projectId}/endpoints/${id}/verify`);
  },

  skipVerification: (projectId: string, id: string, reason?: string): Promise<EndpointResponse> => {
    return http.post<EndpointResponse>(`/api/v1/projects/${projectId}/endpoints/${id}/skip-verification`, { reason });
  },
};

export interface MtlsConfigRequest {
  clientCert: string;
  clientKey: string;
  caCert?: string;
}

export interface VerificationResponse {
  success: boolean;
  message: string;
  status: 'PENDING' | 'VERIFIED' | 'FAILED' | 'SKIPPED';
}
