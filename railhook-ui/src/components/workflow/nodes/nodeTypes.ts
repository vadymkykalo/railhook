import TriggerNode from './TriggerNode';
import FilterNode from './FilterNode';
import TransformNode from './TransformNode';
import HttpNode from './HttpNode';
import SlackNode from './SlackNode';
import DeliveryNode from './DeliveryNode';
import BranchNode from './BranchNode';
import DelayNode from './DelayNode';
import CreateEventNode from './CreateEventNode';

export const nodeTypes = {
  webhookTrigger: TriggerNode,
  filter: FilterNode,
  transform: TransformNode,
  http: HttpNode,
  slack: SlackNode,
  delivery: DeliveryNode,
  branch: BranchNode,
  delay: DelayNode,
  createEvent: CreateEventNode,
};

/** The colour says the role, not the type: per-type hues competed and looked like status hues. */
export type NodeRole = 'trigger' | 'logic' | 'action';

export const NODE_ROLE_COLOR: Record<NodeRole, string> = {
  trigger: 'hsl(var(--primary))',
  logic: 'hsl(var(--muted-foreground))',
  action: 'hsl(var(--foreground))',
};

/** Typed off the map so a template for a type with no component fails to compile. */
export type WorkflowNodeType = keyof typeof nodeTypes;

export interface NodeTemplate {
  type: WorkflowNodeType;
  icon: string;
  role: NodeRole;
  defaultData: Record<string, unknown>;
}

// No `label`: nodes fall back to t() so a fresh node renders in the active locale.
export const nodeTemplates: NodeTemplate[] = [
  {
    type: 'webhookTrigger',
    icon: '⚡',
    role: 'trigger',
    defaultData: { eventTypePattern: '*' },
  },
  {
    type: 'filter',
    icon: '🔀',
    role: 'logic',
    defaultData: { conditions: null },
  },
  {
    type: 'transform',
    icon: '🔄',
    role: 'logic',
    defaultData: { template: '{}' },
  },
  {
    type: 'http',
    icon: '🌐',
    role: 'action',
    defaultData: { url: '', method: 'POST', headers: {}, body: null, timeout: 30 },
  },
  {
    type: 'slack',
    icon: '💬',
    role: 'action',
    defaultData: { webhookUrl: '', message: '', channel: '' },
  },
  {
    type: 'delivery',
    icon: '📦',
    role: 'action',
    defaultData: { endpointId: '' },
  },
  {
    type: 'branch',
    icon: '🔀',
    role: 'logic',
    defaultData: { conditions: null },
  },
  {
    type: 'delay',
    icon: '⏱️',
    role: 'logic',
    defaultData: { delaySeconds: 5 },
  },
  {
    type: 'createEvent',
    icon: '📤',
    role: 'action',
    defaultData: { projectId: '', eventType: '', payloadTemplate: '' },
  },
];
