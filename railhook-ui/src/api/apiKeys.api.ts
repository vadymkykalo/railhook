import { http } from './http';
import type { PageResponse } from '../types/api.types';

export type ApiKeyScope = 'READ_WRITE' | 'READ_ONLY';

export interface ApiKeyRequest {
  name: string;
  scope?: ApiKeyScope;
  expiresAt?: string;
}

export interface ApiKeyRotateRequest {
  /** Omitted means the server's 24; 0 cuts the old key off at once. */
  gracePeriodHours?: number;
  expiresAt?: string;
}

export interface ApiKeyResponse {
  id: string;
  projectId: string;
  name: string;
  keyPrefix: string;
  lastUsedAt: string | null;
  createdAt: string;
  revokedAt: string | null;
  expiresAt: string | null;
  scope: string;
  key?: string;
  rotatedAt: string | null;
  replacedById: string | null;
}

export const apiKeysApi = {
  list: (projectId: string): Promise<ApiKeyResponse[]> => {
    return http.get<PageResponse<ApiKeyResponse>>(`/api/v1/projects/${projectId}/api-keys?size=1000`)
      .then(page => page.content);
  },

  listPaged: (projectId: string, page = 0, size = 20): Promise<PageResponse<ApiKeyResponse>> => {
    return http.get<PageResponse<ApiKeyResponse>>(`/api/v1/projects/${projectId}/api-keys?page=${page}&size=${size}`);
  },

  create: (projectId: string, data: ApiKeyRequest): Promise<ApiKeyResponse> => {
    return http.post<ApiKeyResponse>(`/api/v1/projects/${projectId}/api-keys`, data);
  },

  rotate: (projectId: string, apiKeyId: string, data: ApiKeyRotateRequest = {}): Promise<ApiKeyResponse> => {
    return http.post<ApiKeyResponse>(`/api/v1/projects/${projectId}/api-keys/${apiKeyId}/rotate`, data);
  },

  revoke: (projectId: string, apiKeyId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/api-keys/${apiKeyId}`);
  },
};
