import { http } from './http';

export type RuleType = 'BUILTIN' | 'CUSTOM';
export type MaskStyle = 'FULL' | 'PARTIAL' | 'HASH';

export interface PiiMaskingRuleResponse {
  id: string;
  projectId: string;
  ruleType: RuleType;
  patternName: string;
  jsonPath?: string;
  maskStyle: MaskStyle;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PiiMaskingRuleRequest {
  patternName: string;
  jsonPath?: string;
  maskStyle: MaskStyle;
  enabled?: boolean;
}

export const piiRulesApi = {
  list: (projectId: string): Promise<PiiMaskingRuleResponse[]> => {
    return http.get<PiiMaskingRuleResponse[]>(`/api/v1/projects/${projectId}/pii-rules`);
  },

  create: (projectId: string, data: PiiMaskingRuleRequest): Promise<PiiMaskingRuleResponse> => {
    return http.post<PiiMaskingRuleResponse>(`/api/v1/projects/${projectId}/pii-rules`, data);
  },

  update: (projectId: string, ruleId: string, data: PiiMaskingRuleRequest): Promise<PiiMaskingRuleResponse> => {
    return http.put<PiiMaskingRuleResponse>(`/api/v1/projects/${projectId}/pii-rules/${ruleId}`, data);
  },

  delete: (projectId: string, ruleId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/projects/${projectId}/pii-rules/${ruleId}`);
  },

  seedDefaults: (projectId: string): Promise<PiiMaskingRuleResponse[]> => {
    return http.post<PiiMaskingRuleResponse[]>(`/api/v1/projects/${projectId}/pii-rules/seed-defaults`);
  },

  /**
   * The sanitized form of a payload, as text.
   *
   * <p>The endpoint answers `text/plain`, but axios parses any body that looks
   * like JSON whatever the content type says — so this resolved an object while
   * its signature promised a string, and rendering that threw React error #31
   * over the whole page. Normalising here keeps the promise true rather than
   * asking every caller to guess which it got.
   */
  preview: async (projectId: string, payload: string): Promise<string> => {
    const sanitized = await http.post<unknown>(
      `/api/v1/projects/${projectId}/pii-rules/preview`,
      payload,
    );
    return typeof sanitized === 'string' ? sanitized : JSON.stringify(sanitized, null, 2);
  },
};
