import { http } from './http';

export interface TunnelSessionResponse {
  id: string;
  organizationId: string;
  userId: string;
  projectId: string | null;
  publicSlug: string;
  publicUrl: string;
  localPort: number;
  status: 'ACTIVE' | 'CLOSED' | 'EXPIRED';
  createdAt: string;
  lastHeartbeat: string | null;
  closedAt: string | null;
  clientInfo: string | null;
}

export interface TunnelStatusResponse {
  activeTunnels: number;
  pendingRequests: number;
  myTunnels: TunnelSessionResponse[];
}

export const tunnelsApi = {
  list: (): Promise<TunnelSessionResponse[]> => {
    return http.get<TunnelSessionResponse[]>('/api/v1/tunnels');
  },

  status: (): Promise<TunnelStatusResponse> => {
    return http.get<TunnelStatusResponse>('/api/v1/tunnels/status');
  },

  close: (sessionId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/tunnels/${sessionId}`);
  },
};
