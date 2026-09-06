import { http } from './http';

export interface PlanResponse {
  id: string;
  name: string;
  displayName: string;
  maxEventsPerMonth: number;
  maxEndpointsPerProject: number;
  maxProjects: number;
  maxMembers: number;
  maxActiveTunnels: number;
  rateLimitPerSecond: number;
  maxRetentionDays: number;
  features: Record<string, boolean>;
  priceMonthlyCents: number;
  priceYearlyCents: number;
}

export interface UsageSnapshot {
  eventsThisMonth: number;
  eventsLimit: number;
  projects: number;
  projectsLimit: number;
}

export interface OrganizationBillingResponse {
  organizationId: string;
  plan: PlanResponse;
  billingStatus: string;
  billingEmail: string | null;
  usage: UsageSnapshot;
}

export interface ResourceUsage {
  current: number;
  limit: number;
  percentUsed: number;
}

export interface UsageResponse {
  events: ResourceUsage;
  endpoints: ResourceUsage;
  projects: ResourceUsage;
  members: ResourceUsage;
  rateLimitPerSecond: number;
  retentionDays: number;
  periodStart: string;
  periodEnd: string;
}

export interface InvoiceResponse {
  id: string;
  status: string;
  amountCents: number;
  currency: string;
  planName: string;
  periodStart: string;
  periodEnd: string;
  paidAt: string | null;
  invoiceUrl: string | null;
}

export const billingApi = {
  listPlans: (): Promise<PlanResponse[]> =>
    http.get<PlanResponse[]>('/api/v1/billing/plans'),

  getOrganizationBilling: (): Promise<OrganizationBillingResponse> =>
    http.get<OrganizationBillingResponse>('/api/v1/billing/organization'),

  updateBillingInfo: (data: { billingEmail: string }): Promise<OrganizationBillingResponse> =>
    http.put<OrganizationBillingResponse>('/api/v1/billing/organization', data),

  changePlan: (data: { planName: string }): Promise<OrganizationBillingResponse> =>
    http.put<OrganizationBillingResponse>('/api/v1/billing/organization/plan', data),

  getUsage: (): Promise<UsageResponse> =>
    http.get<UsageResponse>('/api/v1/billing/usage'),

  listInvoices: (): Promise<InvoiceResponse[]> =>
    http.get<InvoiceResponse[]>('/api/v1/billing/invoices'),

  createCheckout: (data: { planName: string; billingInterval?: string; providerCode?: string; successUrl: string; cancelUrl: string }): Promise<{ url: string }> =>
    http.post<{ url: string }>('/api/v1/billing/checkout', data),

  createPortal: (returnUrl: string): Promise<{ url: string }> =>
    http.post<{ url: string }>(`/api/v1/billing/portal?returnUrl=${encodeURIComponent(returnUrl)}`),

  cancelSubscription: (): Promise<void> =>
    http.post<void>('/api/v1/billing/cancel'),
};
