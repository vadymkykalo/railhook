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

/**
 * Locale parity (en vs uk) cannot catch a key missing from *both* files, and
 * TypeScript cannot check a template-literal `t()` key against JSON. Between
 * them sits a whole class of bug that reaches production looking like
 * `events.deliveryStatus.unknown` printed where a label should be.
 *
 * Everything below renders a backend enum value through an interpolated key.
 * The enums come from the committed openapi.yaml, which CI already proves has
 * not drifted from what the API serves — so this list stays true on its own
 * rather than being a copy somebody has to remember to update.
 */

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const spec = yaml.load(readFileSync(resolve(root, '../openapi.yaml'), 'utf8')) as {
  components: { schemas: Record<string, { properties?: Record<string, { enum?: string[] }> }> };
};

/**
 * Locale namespace → the schema property whose enum it has to cover, and the values of that
 * enum the UI never renders.
 *
 * <p>The fourth element exists for one real case rather than as a general escape hatch:
 * MembershipRole carries API_KEY, which is what an API-key caller authenticates as and never
 * something a membership row is. A label for it would be a dead entry of exactly the kind the
 * "no label for a value the API cannot return" assertion is here to prevent, so the value is
 * named as unrendered and then required to be absent — which keeps both directions honest
 * instead of quietly widening one of them.
 */
const ENUM_BACKED: Array<[namespace: string, schema: string, property: string, unrendered?: string[]]> = [
  ['billing.statuses', 'OrganizationBillingResponse', 'billingStatus'],
  ['replay.status', 'ReplaySessionResponse', 'status'],
  ['workflows.execStatus', 'WorkflowExecutionResponse', 'status'],
  ['workflows.stepStatus', 'StepExecutionResponse', 'status'],
  ['incidents.statuses', 'IncidentResponse', 'status'],
  ['alerts.severities', 'AlertRuleResponse', 'severity'],
  ['alerts.channels', 'AlertRuleResponse', 'channel'],
  ['piiRules.maskStyles', 'PiiMaskingRuleResponse', 'maskStyle'],
  // Added after 2.10.0 shipped four sets of raw keys to a customer's screen. Each of these is
  // a t(`namespace.${value}`) call with the same drift risk as the four that broke; the first
  // is the most-rendered status label in the product, and until DeliveryResponse.status was
  // typed as its enum rather than a String, the spec did not say enough for this test to
  // check it at all.
  ['deliveries.status', 'DeliveryResponse', 'status'],
  ['members.statuses', 'MemberResponse', 'status'],
  ['roles', 'MemberResponse', 'role', ['API_KEY']],
  ['rules.actionTypes', 'RuleActionResponse', 'type'],
  ['workflows.triggerTypes', 'WorkflowResponse', 'triggerType'],
  // The dashboard says the same five statuses in its own words -- "Abandoned" where the
  // deliveries table says "DLQ" -- so it is a second set of labels over one enum, and it
  // drifts separately.
  ['dashboard.inFlight.status', 'DeliveryResponse', 'status'],
  ['analytics.endpointStatus', 'EndpointPerformance', 'status'],
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

  /* Not a backend enum: derived in the page from a rollup the API does not
     serve. STATUS_KIND is a Record over the union, so TypeScript guarantees
     its keys are the complete set — which is why the test reads it rather
     than restating the values. */
  it.each([['en', en], ['uk', uk]] as const)(
    'events.deliveryStatus has a label for every derived status (%s)',
    (_name, locale) => {
      const labels = labelsUnder(locale, 'events.deliveryStatus');
      expect(Object.keys(STATUS_KIND).filter((k) => !(k in labels))).toEqual([]);
    },
  );

  /* The dashboard's one-word answer to "is this project healthy". Derived by
     verdictOfDeliveryStats from the delivery rollup, so it is a StatusKind and
     nothing the API names. STATUS_TEXT is a Record over that union, which makes
     its keys the complete set for the same reason STATUS_KIND is.

     Only the forward direction is checked: the namespace also carries `label`,
     `detail` and `idleDetail`, which are prose around the verdict rather than
     values of it. */
  it.each([['en', en], ['uk', uk]] as const)(
    'dashboard.verdict has a label for every status kind (%s)',
    (_name, locale) => {
      const labels = labelsUnder(locale, 'dashboard.verdict');
      expect(Object.keys(STATUS_TEXT).filter((k) => !(k in labels))).toEqual([]);
    },
  );

  /* Workflow node types. Not an enum at either end: the nine types are a
     frontend catalogue, and the canvas renders each one's name and its one-line
     description from the locale whenever the node carries no label of its own —
     which is every node the moment it is dropped. A missing entry here is a
     palette item reading `workflows.nodeTypes.slack.label`.

     `nodeTemplates` is typed against the map React Flow is handed, so a
     template for a type with no component does not compile and this list cannot
     fall behind the canvas. */
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
