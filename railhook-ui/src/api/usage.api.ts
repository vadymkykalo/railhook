import { http } from './http';

export interface LiveUsage {
  totalEvents: number;
  totalDeliveries: number;
  successfulDeliveries: number;
  failedDeliveries: number;
  dlqDeliveries: number;
  pendingDeliveries: number;
  totalIncomingEvents: number;
  totalIncomingForwards: number;
  activeEndpoints: number;
  activeIncomingSources: number;
  activeAlertRules: number;
}

export interface DailyUsage {
  date: string;
  eventsCount: number;
  deliveriesCount: number;
  successfulDeliveries: number;
  failedDeliveries: number;
  dlqCount: number;
  incomingEventsCount: number;
  incomingForwardsCount: number;
  avgLatencyMs: number | null;
  p95LatencyMs: number | null;
}

export interface UsageStatsResponse {
  current: LiveUsage;
  history: DailyUsage[];
}

export const usageApi = {
  getUsage: (projectId: string, days = 30): Promise<UsageStatsResponse> =>
    http.get(`/api/v1/projects/${projectId}/usage?days=${days}`),
};
