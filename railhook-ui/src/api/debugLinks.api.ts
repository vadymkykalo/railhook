import { http } from './http';
import axios from 'axios';

export interface SharedDebugLinkResponse {
  id: string;
  projectId: string;
  eventId: string;
  token: string;
  shareUrl: string;
  expiresAt: string;
  createdAt: string;
  viewCount: number;
}

export interface SharedDebugLinkRequest {
  expiryHours?: number;
}

export interface SharedDebugLinkPublicResponse {
  eventType: string;
  sanitizedPayload: string;
  eventCreatedAt: string;
  linkExpiresAt: string;
  projectName: string;
}

const API_URL = import.meta.env.VITE_API_URL || '';

export const debugLinksApi = {
  create: (projectId: string, eventId: string, data?: SharedDebugLinkRequest): Promise<SharedDebugLinkResponse> => {
    return http.post<SharedDebugLinkResponse>(
      `/api/v1/projects/${projectId}/events/${eventId}/debug-links`,
      data || { expiryHours: 24 }
    );
  },

  listForEvent: (projectId: string, eventId: string): Promise<SharedDebugLinkResponse[]> => {
    return http.get<SharedDebugLinkResponse[]>(
      `/api/v1/projects/${projectId}/events/${eventId}/debug-links`
    );
  },

  delete: (projectId: string, linkId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/debug-links/${linkId}`);
  },

  viewPublic: async (token: string): Promise<SharedDebugLinkPublicResponse> => {
    const response = await axios.get<SharedDebugLinkPublicResponse>(
      `${API_URL}/api/v1/public/debug/${token}`
    );
    return response.data;
  },
};
