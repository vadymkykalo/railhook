import axios, { type AxiosInstance } from 'axios';
import type {
  DeliveryAttemptResponse, PageResponse, PortalDeliveryResponse, PortalEndpointRequest,
  PortalEndpointResponse, PortalSessionInfoResponse,
} from '../types/api.types';

/**
 * The customer portal's own client, deliberately not the dashboard's `http.ts`.
 *
 * That one carries a dashboard session: a bearer token from the auth store and, on a 401, a
 * refresh through the `refresh_token` cookie. The portal has neither and must never borrow them
 * — it runs for someone who is not a Railhook user, possibly inside another site's iframe. Its
 * one credential is the portal session token, held here in memory only: not in localStorage, not
 * in a cookie, so it ends with the page and no other page on this origin can read it.
 *
 * A 401 is final. There is nothing to refresh with; the customer's page opens a new session.
 */
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

/** Called on any 401: the session expired or was revoked, and nothing can renew it from here. */
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
