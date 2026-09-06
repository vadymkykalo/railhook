import { http } from './http';
import type { PageResponse } from '../types/api.types';

export type ApiKeyScope = 'READ_WRITE' | 'READ_ONLY';

export interface ApiKeyRequest {
  name: string;
  scope?: ApiKeyScope;
  expiresAt?: string;
}

export interface ApiKeyRotateRequest {
  /**
   * Hours the outgoing key keeps working. Omitted means the server's default of 24; 0 cuts it
   * off immediately, which is the rotation you do after a suspected leak.
   */
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
  /** Set on a key that has been rotated away; with expiresAt it is the grace window. */
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

  /**
   * Issues a replacement and puts this key into a grace window during which both work, so a
   * rollover is not a create-then-revoke race the user has to time by hand. The plaintext of
   * the new key comes back exactly once, in this response.
   */
  rotate: (projectId: string, apiKeyId: string, data: ApiKeyRotateRequest = {}): Promise<ApiKeyResponse> => {
    return http.post<ApiKeyResponse>(`/api/v1/projects/${projectId}/api-keys/${apiKeyId}/rotate`, data);
  },

  revoke: (projectId: string, apiKeyId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/api-keys/${apiKeyId}`);
  },
};
