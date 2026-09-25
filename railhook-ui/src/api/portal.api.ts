import axios, { type AxiosInstance } from 'axios';
import type {
  DeliveryAttemptResponse, PageResponse, PortalDeliveryResponse, PortalEndpointRequest,
  PortalEndpointResponse, PortalSessionInfoResponse,
} from '../types/api.types';

/** Not http.ts: the portal has no dashboard session, and its token lives in memory only. */
let token: string | null = null;

const client: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '',
  timeout: 30_000,
});

client.interceptors.request.use((config) => {
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

let onUnauthorized: (() => void) | null = null;

client.interceptors.response.use(undefined, (error) => {
  if (error?.response?.status === 401) onUnauthorized?.();
  return Promise.reject(error);
});

export function setPortalToken(value: string | null) {
  token = value;
}

export function onPortalUnauthorized(callback: (() => void) | null) {
  onUnauthorized = callback;
}

export function hasPortalToken(): boolean {
  return token !== null;
}

async function data<T>(request: Promise<{ data: T }>): Promise<T> {
  return (await request).data;
}

export interface PortalDeliveryFilters {
  status?: PortalDeliveryResponse['status'];
  endpointId?: string;
  page?: number;
  size?: number;
}

export const portalApi = {
  session: (): Promise<PortalSessionInfoResponse> => data(client.get('/api/v1/portal/session')),

  listEndpoints: (): Promise<PortalEndpointResponse[]> => data(client.get('/api/v1/portal/endpoints')),

  createEndpoint: (body: PortalEndpointRequest): Promise<PortalEndpointResponse> =>
    data(client.post('/api/v1/portal/endpoints', body)),

  updateEndpoint: (id: string, body: PortalEndpointRequest): Promise<PortalEndpointResponse> =>
    data(client.put(`/api/v1/portal/endpoints/${id}`, body)),

  deleteEndpoint: (id: string): Promise<void> => data(client.delete(`/api/v1/portal/endpoints/${id}`)),

  rotateSecret: (id: string): Promise<PortalEndpointResponse> =>
    data(client.post(`/api/v1/portal/endpoints/${id}/rotate-secret`)),

  listDeliveries: (filters: PortalDeliveryFilters): Promise<PageResponse<PortalDeliveryResponse>> =>
    data(client.get('/api/v1/portal/deliveries', {
      params: {
        status: filters.status || undefined,
        endpointId: filters.endpointId || undefined,
        page: filters.page ?? 0,
        size: filters.size ?? 20,
      },
    })),

  listAttempts: (deliveryId: string): Promise<DeliveryAttemptResponse[]> =>
    data(client.get(`/api/v1/portal/deliveries/${deliveryId}/attempts`)),

  retryDelivery: (deliveryId: string): Promise<void> =>
    data(client.post(`/api/v1/portal/deliveries/${deliveryId}/retry`)),
};
