import { http } from './http';
import type { PageResponse } from '../types/api.types';

export type IncidentStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED';
export type IncidentTimelineType = 'FAILURE' | 'RETRY' | 'REPLAY' | 'NOTE' | 'STATUS_CHANGE';

export interface TimelineEntry {
  id: string;
  entryType: IncidentTimelineType;
  title: string;
  detail: string | null;
  deliveryId: string | null;
  endpointId: string | null;
  createdAt: string;
}

export interface IncidentResponse {
  id: string;
  projectId: string;
  title: string;
  status: IncidentStatus;
  severity: string;
  rcaNotes: string | null;
  resolvedAt: string | null;
  createdAt: string;
  updatedAt: string;
  timeline: TimelineEntry[] | null;
}

export interface IncidentCounts {
  count: number;
  investigating: number;
  critical: number;
}

export interface IncidentRequest {
  title: string;
  severity?: string;
  status?: IncidentStatus;
  rcaNotes?: string;
}

export interface TimelineEntryRequest {
  entryType: IncidentTimelineType;
  title: string;
  detail?: string;
  deliveryId?: string;
  endpointId?: string;
}

export const incidentsApi = {
  list: (projectId: string, openOnly = false, page = 0, size = 20): Promise<PageResponse<IncidentResponse>> =>
    http.get(`/api/v1/projects/${projectId}/incidents?openOnly=${openOnly}&page=${page}&size=${size}`),

  get: (projectId: string, incidentId: string): Promise<IncidentResponse> =>
    http.get(`/api/v1/projects/${projectId}/incidents/${incidentId}`),

  create: (projectId: string, data: IncidentRequest): Promise<IncidentResponse> =>
    http.post(`/api/v1/projects/${projectId}/incidents`, data),

  update: (projectId: string, incidentId: string, data: Partial<IncidentRequest>): Promise<IncidentResponse> =>
    http.put(`/api/v1/projects/${projectId}/incidents/${incidentId}`, data),

  addTimeline: (projectId: string, incidentId: string, data: TimelineEntryRequest): Promise<IncidentResponse> =>
    http.post(`/api/v1/projects/${projectId}/incidents/${incidentId}/timeline`, data),

  /** count is OPEN plus INVESTIGATING; derived from list() it counted one filtered page. */
  countOpen: (projectId: string): Promise<IncidentCounts> =>
    http.get(`/api/v1/projects/${projectId}/incidents/open-count`),
};
