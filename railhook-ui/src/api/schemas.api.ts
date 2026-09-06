import { http } from './http';

export interface EventTypeCatalogResponse {
  id: string;
  projectId: string;
  name: string;
  description: string | null;
  latestVersion: number | null;
  activeVersionStatus: string | null;
  hasBreakingChanges: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface EventTypeCatalogRequest {
  name: string;
  description?: string;
}

export type CompatibilityMode = 'NONE' | 'BACKWARD' | 'FORWARD' | 'FULL';

export interface EventSchemaVersionResponse {
  id: string;
  eventTypeId: string;
  version: number;
  schemaJson: string;
  fingerprint: string;
  status: string;
  compatibilityMode: CompatibilityMode;
  description: string | null;
  createdBy: string | null;
  createdAt: string;
}

export interface EventSchemaVersionRequest {
  schemaJson: string;
  compatibilityMode?: CompatibilityMode;
  description?: string;
}

export interface SchemaChangeResponse {
  id: string;
  eventTypeId: string;
  eventTypeName: string | null;
  fromVersionId: string | null;
  fromVersion: number | null;
  toVersionId: string;
  toVersion: number | null;
  changeSummary: string;
  breaking: boolean;
  createdAt: string;
}

export const schemasApi = {
  listEventTypes: (projectId: string): Promise<EventTypeCatalogResponse[]> =>
    http.get(`/api/v1/projects/${projectId}/schemas`),

  createEventType: (projectId: string, data: EventTypeCatalogRequest): Promise<EventTypeCatalogResponse> =>
    http.post(`/api/v1/projects/${projectId}/schemas`, data),

  getEventType: (projectId: string, eventTypeId: string): Promise<EventTypeCatalogResponse> =>
    http.get(`/api/v1/projects/${projectId}/schemas/${eventTypeId}`),

  updateEventType: (projectId: string, eventTypeId: string, data: EventTypeCatalogRequest): Promise<EventTypeCatalogResponse> =>
    http.put(`/api/v1/projects/${projectId}/schemas/${eventTypeId}`, data),

  deleteEventType: (projectId: string, eventTypeId: string): Promise<void> =>
    http.delete(`/api/v1/projects/${projectId}/schemas/${eventTypeId}`),

  listVersions: (projectId: string, eventTypeId: string): Promise<EventSchemaVersionResponse[]> =>
    http.get(`/api/v1/projects/${projectId}/schemas/${eventTypeId}/versions`),

  createVersion: (projectId: string, eventTypeId: string, data: EventSchemaVersionRequest): Promise<EventSchemaVersionResponse> =>
    http.post(`/api/v1/projects/${projectId}/schemas/${eventTypeId}/versions`, data),

  getVersion: (projectId: string, eventTypeId: string, versionId: string): Promise<EventSchemaVersionResponse> =>
    http.get(`/api/v1/projects/${projectId}/schemas/${eventTypeId}/versions/${versionId}`),

  promoteVersion: (projectId: string, eventTypeId: string, versionId: string): Promise<EventSchemaVersionResponse> =>
    http.post(`/api/v1/projects/${projectId}/schemas/${eventTypeId}/versions/${versionId}/promote`),

  deprecateVersion: (projectId: string, eventTypeId: string, versionId: string): Promise<EventSchemaVersionResponse> =>
    http.post(`/api/v1/projects/${projectId}/schemas/${eventTypeId}/versions/${versionId}/deprecate`),

  listChanges: (projectId: string, eventTypeId: string): Promise<SchemaChangeResponse[]> =>
    http.get(`/api/v1/projects/${projectId}/schemas/${eventTypeId}/changes`),

  listProjectChanges: (projectId: string): Promise<SchemaChangeResponse[]> =>
    http.get(`/api/v1/projects/${projectId}/schemas/changes`),
};
