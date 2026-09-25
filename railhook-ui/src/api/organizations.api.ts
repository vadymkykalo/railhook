import { http } from './http';
import { withJsonErrorBody } from './blobErrorBody';

export interface OrganizationResponse {
  id: string;
  name: string;
  createdAt: string;
}

export const organizationsApi = {
  list: (): Promise<OrganizationResponse[]> => {
    return http.get<OrganizationResponse[]>('/api/v1/orgs');
  },

  get: (orgId: string): Promise<OrganizationResponse> => {
    return http.get<OrganizationResponse>(`/api/v1/orgs/${orgId}`);
  },

  update: (orgId: string, data: { name: string }): Promise<OrganizationResponse> => {
    return http.put<OrganizationResponse>(`/api/v1/orgs/${orgId}`, data);
  },

  delete: (orgId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/orgs/${orgId}`);
  },

  exportData: (orgId: string): Promise<Blob> => {
    return withJsonErrorBody(http.getBlob(`/api/v1/orgs/${orgId}/export`));
  },
};
