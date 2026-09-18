import { http } from './http';
import type { PageResponse } from '../types/api.types';

/**
 * The platform admin API: every organization and account on the deployment.
 *
 * Reached only by a person whose verified address is in PLATFORM_ADMIN_EMAILS and who signed in
 * within the last 12 hours — the server checks all of it on every request, so nothing here is a
 * gate, only a client.
 */

/** `PASSWORD`, or an identity provider's name such as `GOOGLE`. */
export type SignInMethod = string;
export type AdminUserStatus = 'ACTIVE' | 'PENDING_VERIFICATION' | 'DISABLED';
export type AdminRole = 'OWNER' | 'DEVELOPER' | 'VIEWER' | 'API_KEY';
export type AdminMembershipStatus = 'INVITED' | 'ACTIVE' | 'DISABLED';

export interface AdminSignup {
  userId: string;
  email: string;
  fullName: string | null;
  emailVerified: boolean;
  status: AdminUserStatus;
  signInMethods: SignInMethod[];
  organizationId: string | null;
  organizationName: string | null;
  createdAt: string;
}

export interface PlatformDay {
  /** `YYYY-MM-DD`. */
  date: string;
  signups: number;
  events: number;
}

/** How far the last 30 days' sign-ups got; each figure is a subset of the one before it. */
export interface PlatformActivation {
  signups: number;
  verified: number;
  organizations: number;
  withProject: number;
  withEvent: number;
}

export interface PlatformOverview {
  organizations: number;
  suspendedOrganizations: number;
  users: number;
  signupsToday: number;
  signups7d: number;
  signups30d: number;
  eventsToday: number;
  events30d: number;
  deliveriesSucceeded24h: number;
  deliveriesFailed24h: number;
  activeTunnels: number;
  organizationsNearQuota: number;
  /** Oldest first, one entry for each of the last 30 days, today included. */
  daily30d: PlatformDay[];
  activation30d: PlatformActivation;
  recentSignups: AdminSignup[];
  generatedAt: string;
}

export interface AdminOrganization {
  id: string;
  name: string;
  planName: string | null;
  billingStatus: string;
  createdAt: string;
  ownerEmail: string | null;
  projectCount: number;
  memberCount: number;
  eventsThisMonth: number;
  eventsLimit: number;
  suspendedAt: string | null;
  suspensionReason: string | null;
  suspendedBy: string | null;
}

export interface AdminResourceUsage {
  current: number;
  limit: number;
  percentUsed: number;
}

export interface AdminUsage {
  events: AdminResourceUsage;
  endpoints: AdminResourceUsage;
  projects: AdminResourceUsage;
  members: AdminResourceUsage;
  rateLimitPerSecond: number;
  retentionDays: number;
  periodStart: string;
  periodEnd: string;
}

export interface AdminMember {
  userId: string;
  email: string | null;
  fullName: string | null;
  role: AdminRole;
  membershipStatus: AdminMembershipStatus;
  emailVerified: boolean;
  userStatus: AdminUserStatus | null;
  signInMethods: SignInMethod[];
  joinedAt: string;
  lastSeenAt: string | null;
  /** Listed in PLATFORM_ADMIN_EMAILS, verified and active — decided by the server. */
  platformAdmin: boolean;
}

export interface AdminProject {
  id: string;
  name: string;
  createdAt: string;
}

export interface AdminAuditEntry {
  id: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  actorEmail: string | null;
  status: string;
  clientIp: string | null;
  createdAt: string;
}

export interface AdminUser {
  id: string;
  email: string;
  fullName: string | null;
  emailVerified: boolean;
  status: AdminUserStatus;
  signInMethods: SignInMethod[];
  organizations: { id: string; name: string; role: AdminRole }[];
  createdAt: string;
  lastSeenAt: string | null;
  /** Listed in PLATFORM_ADMIN_EMAILS, verified and active — decided by the server. */
  platformAdmin: boolean;
}

export interface OrganizationFilters {
  search?: string;
  suspendedOnly?: boolean;
}

function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '' && value !== false) search.set(key, String(value));
  }
  return search.toString();
}

const ORGS = '/api/v1/admin/organizations';

export const platformAdminApi = {
  overview: () => http.get<PlatformOverview>('/api/v1/admin/overview'),

  organizations: (page: number, size: number, filters: OrganizationFilters = {}) =>
    http.get<PageResponse<AdminOrganization>>(`${ORGS}?${query({ page, size, ...filters })}`),

  organization: (id: string) => http.get<AdminOrganization>(`${ORGS}/${id}`),

  usage: (id: string) => http.get<AdminUsage>(`${ORGS}/${id}/usage`),

  members: (id: string, page = 0, size = 50) =>
    http.get<PageResponse<AdminMember>>(`${ORGS}/${id}/members?${query({ page, size })}`),

  projects: (id: string, page = 0, size = 50) =>
    http.get<PageResponse<AdminProject>>(`${ORGS}/${id}/projects?${query({ page, size })}`),

  auditLog: (id: string, page = 0, size = 20) =>
    http.get<PageResponse<AdminAuditEntry>>(`${ORGS}/${id}/audit-log?${query({ page, size })}`),

  suspend: (id: string, reason: string) => http.post<AdminOrganization>(`${ORGS}/${id}/suspend`, { reason }),

  reinstate: (id: string, reason: string) => http.post<AdminOrganization>(`${ORGS}/${id}/reinstate`, { reason }),

  users: (page: number, size: number, search?: string) =>
    http.get<PageResponse<AdminUser>>(`/api/v1/admin/users?${query({ page, size, search })}`),
};

/**
 * The one refusal worth telling apart: everything about the account holds, but the sign-in is
 * older than the panel accepts. Signing in again fixes it, and "access denied" would not say so.
 */
export function isReauthenticationRequired(error: unknown): boolean {
  const response = (error as { response?: { status?: number; data?: { error?: string } } } | null)?.response;
  return response?.status === 403 && response.data?.error === 'reauthentication_required';
}
