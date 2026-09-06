import { http } from './http';

export interface OrganizationResponse {
  id: string;
  name: string;
  createdAt: string;
}

export const organizationsApi = {
  /**
   * Every organization the caller belongs to. The endpoint has always existed and nothing
   * called it, which is why a second membership was invisible: the switcher is what turns the
   * answer into something you can act on.
   */
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
    return http.getBlob(`/api/v1/orgs/${orgId}/export`);
  },
};
