import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import yaml from 'js-yaml';
import en from '../locales/en.json';
import uk from '../locales/uk.json';
import { STATUS_KIND } from '../../pages/EventsPage';
import { STATUS_TEXT } from '../../components/charts/statusScale';
import { nodeTemplates } from '../../components/workflow/nodes/nodeTypes';

/** Parity cannot catch a key missing from both locales, and TS cannot check template-literal keys. */

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const spec = yaml.load(readFileSync(resolve(root, '../openapi.yaml'), 'utf8')) as {
  components: { schemas: Record<string, { properties?: Record<string, { enum?: string[] }> }> };
};

/** `unrendered` exists for MembershipRole.API_KEY, which no membership row ever is. */
const ENUM_BACKED: Array<[namespace: string, schema: string, property: string, unrendered?: string[]]> = [
  ['billing.statuses', 'OrganizationBillingResponse', 'billingStatus'],
  ['replay.status', 'ReplaySessionResponse', 'status'],
  ['workflows.execStatus', 'WorkflowExecutionResponse', 'status'],
  ['workflows.stepStatus', 'StepExecutionResponse', 'status'],
  ['incidents.statuses', 'IncidentResponse', 'status'],
  ['alerts.severities', 'AlertRuleResponse', 'severity'],
  ['alerts.channels', 'AlertRuleResponse', 'channel'],
  ['piiRules.maskStyles', 'PiiMaskingRuleResponse', 'maskStyle'],
  ['deliveries.status', 'DeliveryResponse', 'status'],
  ['members.statuses', 'MemberResponse', 'status'],
  ['roles', 'MemberResponse', 'role', ['API_KEY']],
  ['rules.actionTypes', 'RuleActionResponse', 'type'],
  ['workflows.triggerTypes', 'WorkflowResponse', 'triggerType'],
  // A second set of labels over the same enum, so it drifts separately.
  ['dashboard.inFlight.status', 'DeliveryResponse', 'status'],
  ['analytics.endpointStatus', 'EndpointPerformance', 'status'],
  ['incomingSources.providerNames', 'IncomingSourceResponse', 'providerType'],
];

function labelsUnder(locale: object, namespace: string): Record<string, unknown> {
  let cur: unknown = locale;
  for (const part of namespace.split('.')) {
    cur = (cur as Record<string, unknown> | undefined)?.[part];
  }
  expect(cur, `${namespace} is missing from the locale file entirely`).toBeTypeOf('object');
  return cur as Record<string, unknown>;
}

describe('interpolated translation keys resolve', () => {
  describe.each(ENUM_BACKED)('%s covers %s.%s', (namespace, schema, property, unrendered = []) => {
    const values = spec.components.schemas[schema]?.properties?.[property]?.enum;
    const rendered = () => values!.filter((v) => !unrendered.includes(v));

    it('the schema still declares the enum this maps to', () => {
      expect(values, `${schema}.${property} has no enum in openapi.yaml — fix this mapping`)
        .toBeDefined();
    });

    it.each([['en', en], ['uk', uk]] as const)('%s has a label for every value', (_name, locale) => {
      const labels = labelsUnder(locale, namespace);
      expect(rendered().filter((v) => !(v in labels))).toEqual([]);
    });

    it('carries no label for a value the API cannot return', () => {
      expect(Object.keys(labelsUnder(en, namespace)).filter((k) => !values!.includes(k))).toEqual([]);
    });

    it.runIf(unrendered.length > 0)('carries no label for a value the UI never renders', () => {
      const labels = labelsUnder(en, namespace);
      expect(unrendered.filter((v) => v in labels)).toEqual([]);
    });
  });

  /* STATUS_KIND is a Record over the union, so its keys are the complete set. */
  it.each([['en', en], ['uk', uk]] as const)(
    'events.deliveryStatus has a label for every derived status (%s)',
    (_name, locale) => {
      const labels = labelsUnder(locale, 'events.deliveryStatus');
      expect(Object.keys(STATUS_KIND).filter((k) => !(k in labels))).toEqual([]);
    },
  );

  /* Forward direction only: the namespace also carries prose keys around the verdict. */
  it.each([['en', en], ['uk', uk]] as const)(
    'dashboard.verdict has a label for every status kind (%s)',
    (_name, locale) => {
      const labels = labelsUnder(locale, 'dashboard.verdict');
      expect(Object.keys(STATUS_TEXT).filter((k) => !(k in labels))).toEqual([]);
    },
  );

  describe('workflows.nodeTypes', () => {
    it.each([['en', en], ['uk', uk]] as const)('%s names and describes every node type', (_name, locale) => {
      const labels = labelsUnder(locale, 'workflows.nodeTypes');
      const missing = nodeTemplates.flatMap(({ type }) => {
        const entry = labels[type] as Record<string, unknown> | undefined;
        return ['label', 'description']
          .filter((field) => typeof entry?.[field] !== 'string')
          .map((field) => `${type}.${field}`);
      });
      expect(missing).toEqual([]);
    });

    it('carries no entry for a node type the palette cannot offer', () => {
      const offered = new Set<string>(nodeTemplates.map((t) => t.type));
      expect(Object.keys(labelsUnder(en, 'workflows.nodeTypes')).filter((k) => !offered.has(k)))
        .toEqual([]);
    });
  });
});
