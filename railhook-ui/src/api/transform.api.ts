import { http } from './http';

export interface TransformPreviewRequest {
  inputPayload: string;
  transformExpression?: string;
  customHeaders?: string;
  template?: string;
  transformationId?: string;
}

export interface TransformPreviewResponse {
  outputPayload: string | null;
  outputHeaders: string | null;
  success: boolean;
  errors: string[];
}

export interface DeliveryDryRunRequest {
  payload: string;
  transformationId?: string;
  payloadTemplate?: string;
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
}

export const transformApi = {
  preview: (projectId: string, data: TransformPreviewRequest): Promise<TransformPreviewResponse> =>
    http.post(`/api/v1/projects/${projectId}/transform-preview`, data),

  deliveryDryRun: (projectId: string, data: DeliveryDryRunRequest): Promise<DeliveryDryRunResponse> =>
    http.post(`/api/v1/projects/${projectId}/transform-preview/delivery-dry-run`, data),
};
