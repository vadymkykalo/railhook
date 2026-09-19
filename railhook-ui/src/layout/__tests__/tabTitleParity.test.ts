import { describe, expect, it } from 'vitest';
import en from '../../i18n/locales/en.json';
import uk from '../../i18n/locales/uk.json';
import { PROJECT_SECTIONS, SETTINGS_SECTION } from '../nav.config';

function lookup(messages: unknown, key: string): unknown {
  return key.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
    messages,
  );
}

/**
 * The page each tab opens, and the key its h1 is read from. A page whose title is the tab's own
 * key is listed too, so that a tab added without a page here fails the completeness check below.
 *
 * The audit behind this: "Rules" opened "Rules Engine", "Time Machine" opened "Event Time
 * Machine", "Profile" opened "Settings" — a newcomer clicks one word and lands on another, and
 * wonders whether they are where they meant to be.
 */
const TITLE_KEY_OF: Record<string, string> = {
  'nav.connections': 'connections.title',
  'nav.endpoints': 'endpoints.title',
  'nav.consumers': 'consumers.title',
  'nav.subscriptions': 'subscriptions.title',
  'nav.incomingSources': 'incomingSources.title',
  'nav.transformations': 'transformations.title',
  'nav.rules': 'rules.title',
  'nav.schemas': 'schemas.title',
  'nav.piiRules': 'piiRules.title',
  'nav.outgoingEvents': 'nav.outgoingEvents',
  'nav.incomingEvents': 'incomingEvents.pageTitle',
  'nav.allDeliveries': 'nav.allDeliveries',
  'nav.dlq': 'nav.dlq',
  'nav.incomingDlq': 'incomingDlq.pageTitle',
  'nav.replay': 'nav.replay',
  'nav.metrics': 'nav.metrics',
  'nav.alerts': 'alerts.title',
  'nav.incidents': 'incidents.title',
  'nav.usage': 'usage.title',
  'nav.testConsole': 'testConsole.title',
  'nav.transformStudio': 'transform.title',
  'nav.eventDiff': 'eventDiff.title',
  'nav.testEndpoints': 'testEndpoints.title',
  'nav.tunnels': 'tunnels.title',
  'nav.profile': 'settings.title',
  'nav.orgSettings': 'orgSettings.title',
  'nav.members': 'members.title',
  'nav.apiKeys': 'apiKeys.title',
  'nav.auditLog': 'auditLog.title',
  'nav.billing': 'billing.title',
};

const tabs = [...PROJECT_SECTIONS.flatMap((section) => section.tabs), ...SETTINGS_SECTION.tabs];

describe('a tab and the page it opens', () => {
  it('knows the page behind every tab', () => {
    expect(tabs.map((tab) => tab.nameKey).filter((key) => !(key in TITLE_KEY_OF))).toEqual([]);
  });

  it.each(Object.entries(TITLE_KEY_OF))('%s is headed by the same words', (tabKey, titleKey) => {
    expect(lookup(en, titleKey), `en: ${titleKey}`).toBe(lookup(en, tabKey));
    expect(lookup(uk, titleKey), `uk: ${titleKey}`).toBe(lookup(uk, tabKey));
  });
});
