import { http } from './http';

export type DiffType = 'ADDED' | 'REMOVED' | 'CHANGED';

export interface DiffEntry {
  path: string;
  type: DiffType;
  leftValue?: unknown;
  rightValue?: unknown;
}

export interface EventDiffResponse {
  leftEventId: string;
  rightEventId: string;
  eventType: string;
  leftCreatedAt: string;
  rightCreatedAt: string;
  leftPayload: string;
  rightPayload: string;
  diffs: DiffEntry[];
}

export interface EventResponse {
  id: string;
  projectId: string;
  eventType: string;
  payload: string;
  createdAt: string;
  deliveriesCreated?: number;
}

export const eventDiffApi = {
  diff: (projectId: string, leftId: string, rightId: string, sanitize = true): Promise<EventDiffResponse> => {
    return http.get<EventDiffResponse>(
      `/api/v1/projects/${projectId}/events/diff?left=${leftId}&right=${rightId}&sanitize=${sanitize}`
    );
  },

  getSanitized: (projectId: string, eventId: string): Promise<EventResponse> => {
    return http.get<EventResponse>(
      `/api/v1/projects/${projectId}/events/${eventId}/sanitized`
    );
  },
};
