import { http } from './http';

export interface DashboardStats {
  deliveryStats: DeliveryStats;
  recentEvents: RecentEvent[];
  endpointHealth: EndpointHealth[];
}

export interface DeliveryStats {
  totalDeliveries: number;
  successfulDeliveries: number;
  failedDeliveries: number;
  pendingDeliveries: number;
  dlqDeliveries: number;
  successRate: number;
}

export interface RecentEvent {
  id: string;
  type: string;
  createdAt: string;
  deliveryCount: number;
}

export interface EndpointHealth {
  id: string;
  url: string;
  enabled: boolean;
  totalDeliveries: number;
  successfulDeliveries: number;
  successRate: number;
}

export interface AnalyticsData {
  timeRange: {
    from: string;
    to: string;
    granularity: string;
  };
  overview: {
    totalEvents: number;
    totalDeliveries: number;
    successfulDeliveries: number;
    failedDeliveries: number;
    successRate: number;
    avgLatencyMs: number;
    p50LatencyMs: number;
    p95LatencyMs: number;
    p99LatencyMs: number;
    eventsPerSecond: number;
    deliveriesPerSecond: number;
  };
  deliveryTimeSeries: TimeSeriesPoint[];
  latencyTimeSeries: TimeSeriesPoint[];
  eventTypeBreakdown: EventTypeBreakdown[];
  endpointPerformance: EndpointPerformance[];
  latencyPercentiles: {
    p50: number;
    p75: number;
    p90: number;
    p95: number;
    p99: number;
    max: number;
  };
}

export interface TimeSeriesPoint {
  timestamp: string;
  total: number;
  success: number;
  failed: number;
  avgLatencyMs?: number;
}

export interface EventTypeBreakdown {
  eventType: string;
  count: number;
  percentage: number;
  successCount: number;
  successRate: number;
}

export interface EndpointPerformance {
  endpointId: string;
  url: string;
  enabled: boolean;
  totalDeliveries: number;
  successfulDeliveries: number;
  failedDeliveries: number;
  successRate: number;
  avgLatencyMs: number;
  p95LatencyMs: number;
  lastDeliveryAt: string | null;
  status: 'HEALTHY' | 'DEGRADED' | 'FAILING';
}

export interface OnboardingStatus {
  hasEndpoints: boolean;
  hasSubscriptions: boolean;
  hasApiKeys: boolean;
  hasEvents: boolean;
  hasDeliveries: boolean;
  hasIncomingSources: boolean;
  hasIncomingDestinations: boolean;
}

export const dashboardApi = {
  getProjectStats: (projectId: string): Promise<DashboardStats> => {
    return http.get<DashboardStats>(`/api/v1/dashboard/projects/${projectId}`);
  },

  getAnalytics: (projectId: string, period: string = '24h'): Promise<AnalyticsData> => {
    return http.get<AnalyticsData>(`/api/v1/dashboard/projects/${projectId}/analytics?period=${period}`);
  },

  getOnboardingStatus: (projectId: string): Promise<OnboardingStatus> => {
    return http.get<OnboardingStatus>(`/api/v1/dashboard/projects/${projectId}/onboarding`);
  },
};
