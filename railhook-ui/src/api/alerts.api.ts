import { http } from './http';
import type { PageResponse } from '../types/api.types';

export type AlertType = 'FAILURE_RATE' | 'DLQ_THRESHOLD' | 'CONSECUTIVE_FAILURES' | 'LATENCY_THRESHOLD';
export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL';
export type AlertChannel = 'IN_APP' | 'EMAIL' | 'WEBHOOK' | 'SLACK';

export interface AlertRuleResponse {
  id: string;
  projectId: string;
  name: string;
  description: string | null;
  alertType: AlertType;
  severity: AlertSeverity;
  channel: AlertChannel;
  thresholdValue: number;
  windowMinutes: number;
  endpointId: string | null;
  enabled: boolean;
  muted: boolean;
  snoozedUntil: string | null;
  webhookUrl: string | null;
  emailRecipients: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AlertRuleRequest {
  name: string;
  description?: string;
  alertType: AlertType;
  severity?: AlertSeverity;
  channel?: AlertChannel;
  thresholdValue: number;
  windowMinutes?: number;
  endpointId?: string;
  enabled?: boolean;
  muted?: boolean;
  snoozedUntil?: string;
  webhookUrl?: string;
  emailRecipients?: string;
}

export interface AlertEventResponse {
  id: string;
  alertRuleId: string;
  projectId: string;
  severity: AlertSeverity;
  title: string;
  message: string | null;
  currentValue: number | null;
  thresholdValue: number | null;
  resolved: boolean;
  resolvedAt: string | null;
  createdAt: string;
}

export const alertsApi = {
  listRules: (projectId: string): Promise<AlertRuleResponse[]> =>
    http.get(`/api/v1/projects/${projectId}/alerts/rules`),

  createRule: (projectId: string, data: AlertRuleRequest): Promise<AlertRuleResponse> =>
    http.post(`/api/v1/projects/${projectId}/alerts/rules`, data),

  updateRule: (projectId: string, ruleId: string, data: Partial<AlertRuleRequest>): Promise<AlertRuleResponse> =>
    http.put(`/api/v1/projects/${projectId}/alerts/rules/${ruleId}`, data),

  deleteRule: (projectId: string, ruleId: string): Promise<void> =>
    http.delete(`/api/v1/projects/${projectId}/alerts/rules/${ruleId}`),

  listEvents: (projectId: string, page = 0, size = 20): Promise<PageResponse<AlertEventResponse>> =>
    http.get(`/api/v1/projects/${projectId}/alerts/events?page=${page}&size=${size}`),

  unresolvedCount: (projectId: string): Promise<{ count: number }> =>
    http.get(`/api/v1/projects/${projectId}/alerts/events/unresolved-count`),

  resolveEvent: (projectId: string, eventId: string): Promise<void> =>
    http.post(`/api/v1/projects/${projectId}/alerts/events/${eventId}/resolve`),

  resolveAll: (projectId: string): Promise<{ resolved: number }> =>
    http.post(`/api/v1/projects/${projectId}/alerts/events/resolve-all`),
};
