import { http } from './http';

export type ActionType = 'ROUTE' | 'TRANSFORM' | 'DROP' | 'TAG';

// ─── Condition Tree DSL ─────────────────────────────────────────

export type GroupOperator = 'AND' | 'OR' | 'NOT';

export type PredicateOperator =
  | 'EQ' | 'NEQ'
  | 'GT' | 'GTE' | 'LT' | 'LTE' | 'BETWEEN'
  | 'CONTAINS' | 'NOT_CONTAINS' | 'STARTS_WITH' | 'ENDS_WITH'
  | 'IN' | 'NOT_IN' | 'REGEX'
  | 'EXISTS' | 'NOT_EXISTS' | 'IS_NULL' | 'NOT_NULL';

export type ValueType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'ARRAY_STRING' | 'ARRAY_NUMBER' | 'DATE_TIME';

export interface ConditionGroup {
  type: 'group';
  op: GroupOperator;
  children: ConditionNode[];
}

export interface ConditionPredicate {
  type: 'predicate';
  field: string;
  operator: PredicateOperator;
  value?: unknown;
  valueType?: ValueType;
  caseInsensitive?: boolean;
}

export type ConditionNode = ConditionGroup | ConditionPredicate;

export interface RuleActionRequest {
  type: ActionType;
  endpointId?: string;
  transformationId?: string;
  config?: Record<string, unknown>;
  sortOrder?: number;
}

export interface RuleActionResponse {
  id: string;
  type: ActionType;
  endpointId: string | null;
  endpointUrl: string | null;
  transformationId: string | null;
  transformationName: string | null;
  config: Record<string, unknown>;
  sortOrder: number;
  createdAt: string;
}

export interface RuleRequest {
  name: string;
  description?: string;
  enabled?: boolean;
  priority?: number;
  eventTypePattern?: string | null;
  conditions?: ConditionNode | null;
  actions?: RuleActionRequest[];
}

export interface RuleResponse {
  id: string;
  projectId: string;
  name: string;
  description: string | null;
  enabled: boolean;
  priority: number;
  eventTypePattern: string | null;
  conditions: ConditionNode | null;
  actions: RuleActionResponse[];
  totalExecutions: number;
  totalMatches: number;
  createdAt: string;
  updatedAt: string;
}

export const rulesApi = {
  list: (projectId: string): Promise<RuleResponse[]> =>
    http.get<RuleResponse[]>(`/api/v1/projects/${projectId}/rules`),

  get: (projectId: string, id: string): Promise<RuleResponse> =>
    http.get<RuleResponse>(`/api/v1/projects/${projectId}/rules/${id}`),

  create: (projectId: string, data: RuleRequest): Promise<RuleResponse> =>
    http.post<RuleResponse>(`/api/v1/projects/${projectId}/rules`, data),

  update: (projectId: string, id: string, data: RuleRequest): Promise<RuleResponse> =>
    http.put<RuleResponse>(`/api/v1/projects/${projectId}/rules/${id}`, data),

  delete: (projectId: string, id: string): Promise<void> =>
    http.delete<void>(`/api/v1/projects/${projectId}/rules/${id}`),

  toggle: (projectId: string, id: string, enabled: boolean): Promise<RuleResponse> =>
    http.patch<RuleResponse>(`/api/v1/projects/${projectId}/rules/${id}/toggle`, { enabled }),
};
