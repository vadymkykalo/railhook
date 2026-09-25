import { http } from './http';
import type {
  McpConsentApproveRequest,
  McpConsentDecisionResponse,
  McpConsentRequestResponse,
  McpGrantResponse,
} from '../types/api.types';

export const mcpAppsApi = {
  getRequest: (requestId: string): Promise<McpConsentRequestResponse> =>
    http.get<McpConsentRequestResponse>(`/api/v1/oauth/requests/${requestId}`),

  approve: (requestId: string, data: McpConsentApproveRequest): Promise<McpConsentDecisionResponse> =>
    http.post<McpConsentDecisionResponse>(`/api/v1/oauth/requests/${requestId}/approve`, data),

  deny: (requestId: string): Promise<McpConsentDecisionResponse> =>
    http.post<McpConsentDecisionResponse>(`/api/v1/oauth/requests/${requestId}/deny`),

  listGrants: (projectId: string): Promise<McpGrantResponse[]> =>
    http.get<McpGrantResponse[]>(`/api/v1/projects/${projectId}/mcp-grants`),

  revokeGrant: (projectId: string, grantId: string): Promise<void> =>
    http.delete<void>(`/api/v1/projects/${projectId}/mcp-grants/${grantId}`),
};
