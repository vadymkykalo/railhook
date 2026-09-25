import { http } from './http';
import type { TransformationKind } from '../types/api.types';

/** A value, not prose, so the UI can say it in the reader's language. */
export type ScriptFailureReason =
  | 'SYNTAX' | 'CONTRACT' | 'RUNTIME' | 'TIMEOUT' | 'MEMORY'
  | 'OUTPUT_TOO_LARGE' | 'SOURCE_TOO_LARGE' | 'UNAVAILABLE';

export interface TransformConsoleLine {
  level: string;
  message: string;
}

export interface TransformPreviewRequest {
  inputPayload: string;
  transformExpression?: string;
  customHeaders?: string;
  template?: string;
  kind?: TransformationKind;
  transformationId?: string;
  eventType?: string;
  eventId?: string;
  url?: string;
}

export interface TransformPreviewResponse {
  outputPayload: string | null;
  outputHeaders: string | null;
  success: boolean;
  errors: string[];
  kind?: TransformationKind;
  console?: TransformConsoleLine[];
  consoleTruncated?: boolean;
  cancelled?: boolean;
  cancelReason?: string | null;
  durationMs?: number;
  /** Already corrected for the sandbox wrapper. */
  errorLine?: number | null;
  errorReason?: ScriptFailureReason | null;
}

export interface DeliveryDryRunRequest {
  payload: string;
  transformationId?: string;
  payloadTemplate?: string;
  kind?: TransformationKind;
  customHeaders?: string;
  endpointId?: string;
  eventType?: string;
}

export interface DeliveryDryRunResponse {
  transformedPayload: string | null;
  requestHeaders: Record<string, string> | null;
  signature: string | null;
  endpointUrl: string | null;
  success: boolean;
  errors: string[];
  transformationName: string | null;
  transformationVersion: number | null;
  transformationKind?: TransformationKind;
  console?: TransformConsoleLine[];
  cancelled?: boolean;
  cancelReason?: string | null;
  durationMs?: number;
  errorLine?: number | null;
  errorReason?: ScriptFailureReason | null;
}

export const transformApi = {
  preview: (projectId: string, data: TransformPreviewRequest): Promise<TransformPreviewResponse> =>
    http.post(`/api/v1/projects/${projectId}/transform-preview`, data),

  deliveryDryRun: (projectId: string, data: DeliveryDryRunRequest): Promise<DeliveryDryRunResponse> =>
    http.post(`/api/v1/projects/${projectId}/transform-preview/delivery-dry-run`, data),
};
