import { http } from './http';

export interface SubscriptionRequest {
  endpointId: string;
  eventType: string;
  enabled: boolean;
  orderingEnabled?: boolean;
  maxAttempts?: number;
  timeoutSeconds?: number;
  retryDelays?: string;
  payloadTemplate?: string;
  customHeaders?: string;
  transformationId?: string | null;
}

export interface SubscriptionResponse {
  id: string;
  projectId: string;
  endpointId: string;
  eventType: string;
  enabled: boolean;
  orderingEnabled: boolean;
  maxAttempts: number;
  timeoutSeconds: number;
  retryDelays: string;
  payloadTemplate: string | null;
  customHeaders: string | null;
  transformationId: string | null;
  transformationName: string | null;
  createdAt: string;
  updatedAt: string;
}

export const subscriptionsApi = {
  list: (projectId: string): Promise<SubscriptionResponse[]> => {
    return http.get<SubscriptionResponse[]>(`/api/v1/projects/${projectId}/subscriptions`);
  },

  get: (projectId: string, subscriptionId: string): Promise<SubscriptionResponse> => {
    return http.get<SubscriptionResponse>(`/api/v1/projects/${projectId}/subscriptions/${subscriptionId}`);
  },

  create: (projectId: string, request: SubscriptionRequest): Promise<SubscriptionResponse> => {
    return http.post<SubscriptionResponse>(`/api/v1/projects/${projectId}/subscriptions`, request);
  },

  update: (projectId: string, subscriptionId: string, request: SubscriptionRequest): Promise<SubscriptionResponse> => {
    return http.put<SubscriptionResponse>(`/api/v1/projects/${projectId}/subscriptions/${subscriptionId}`, request);
  },

  patch: (projectId: string, subscriptionId: string, request: Partial<SubscriptionRequest>): Promise<SubscriptionResponse> => {
    return http.patch<SubscriptionResponse>(`/api/v1/projects/${projectId}/subscriptions/${subscriptionId}`, request);
  },

  delete: (projectId: string, subscriptionId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/subscriptions/${subscriptionId}`);
  },
};
