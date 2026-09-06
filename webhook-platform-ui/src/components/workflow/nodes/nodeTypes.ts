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

/**
 * Nine node types, three roles, and the colour says the role — not the type.
 *
 * The old canvas gave every type its own hex (#f59e0b, #8b5cf6, #e11d48 …),
 * which meant nine hues competing on one surface, none of them legible on ink,
 * and several of them close enough to the reserved status hues to read as a
 * status. What a person actually needs to see at a glance is where a run
 * *starts*, where it *branches or waits*, and where it *leaves the workflow*.
 * The emoji and the label already say which of the nine it is.
 *
 * Every value here is a token expression, so the canvas follows the theme.
 */
export type NodeRole = 'trigger' | 'logic' | 'action';

export const NODE_ROLE_COLOR: Record<NodeRole, string> = {
  /** Where a run begins. The brand accent, because there is exactly one of them. */
  trigger: 'hsl(var(--primary))',
  /** Decides whether, when, or in what shape the run continues. */
  logic: 'hsl(var(--muted-foreground))',
  /** Reaches outside the workflow — an HTTP call, a delivery, a new event. */
  action: 'hsl(var(--foreground))',
};

/**
 * The types React Flow has a component for, and therefore the only types a template may name.
 *
 * Typed off the map rather than restated as a union: a template for a type with no component
 * used to compile and only fail on the canvas, and a typo in it fell through to the locale as a
 * missing key rather than to the compiler.
 */
export type WorkflowNodeType = keyof typeof nodeTypes;

export interface NodeTemplate {
  type: WorkflowNodeType;
  /** i18n key under `workflows.nodeTypes.<type>` — resolve via t() at render time, never hardcode English here. */
  icon: string;
  role: NodeRole;
  defaultData: Record<string, unknown>;
}

// Note: `defaultData` intentionally omits `label` — each node component falls
// back to t(`workflows.nodeTypes.<type>.label`) when `data.label` is unset, so
// a freshly dropped node renders in the active locale instead of baked-in English.
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
