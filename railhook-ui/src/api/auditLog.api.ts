import { http } from './http';

export interface AuditLogEntry {
  id: string;
  action: string;
  resourceType: string;
  resourceId: string | null;
  userId: string | null;
  userEmail: string | null;
  organizationId: string | null;
  status: string;
  errorMessage: string | null;
  durationMs: number | null;
  clientIp: string | null;
  details: string | null;
  createdAt: string;
}

export interface AuditLogPage {
  content: AuditLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface AuditLogFilters {
  action?: string;
  status?: string;
  resourceType?: string;
  from?: string;
  to?: string;
}

function buildFilterParams(filters?: AuditLogFilters): string {
  if (!filters) return '';
  const parts: string[] = [];
  if (filters.action) parts.push(`action=${encodeURIComponent(filters.action)}`);
  if (filters.status) parts.push(`status=${encodeURIComponent(filters.status)}`);
  if (filters.resourceType) parts.push(`resourceType=${encodeURIComponent(filters.resourceType)}`);
  if (filters.from) parts.push(`from=${encodeURIComponent(filters.from)}`);
  if (filters.to) parts.push(`to=${encodeURIComponent(filters.to)}`);
  return parts.length > 0 ? '&' + parts.join('&') : '';
}

export const auditLogApi = {
  list: (page = 0, size = 20, filters?: AuditLogFilters) =>
    http.get<AuditLogPage>(`/api/v1/audit-log?page=${page}&size=${size}${buildFilterParams(filters)}`),

  exportCsv: async (filters?: AuditLogFilters): Promise<Blob> => {
    return http.getBlob(`/api/v1/audit-log/export?_=1${buildFilterParams(filters)}`);
  },
};
