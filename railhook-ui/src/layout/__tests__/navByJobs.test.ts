import { describe, expect, it } from 'vitest';
import { matchRoutes } from 'react-router-dom';
import en from '../../i18n/locales/en.json';
import uk from '../../i18n/locales/uk.json';
import { router } from '../../router';
import { PROJECT_SECTIONS, SETTINGS_SECTION, sectionFor, type NavSection } from '../nav.config';

const P = '/admin/projects/project-1';

function section(nameKey: string): NavSection {
  const found = PROJECT_SECTIONS.find((s) => s.nameKey === nameKey);
  if (!found) throw new Error(`no section ${nameKey}`);
  return found;
}

const tabKeys = (nameKey: string) => section(nameKey).tabs.map((tab) => tab.nameKey);

/**
 * The owner's complaint about the admin: it makes you guess where things are. The rail named
 * internal nouns — one "Connections" entry held nine tabs, sources and PII masking among them — so
 * a person who came to receive Stripe webhooks had to know that "Connections" was where sources
 * lived. The rail now names what people come to do.
 */
describe('the rail, by job', () => {
  it('names the jobs, in the order a newcomer meets them', () => {
    expect(PROJECT_SECTIONS.map((s) => s.nameKey)).toEqual([
      'nav.overview',
      'nav.send',
      'nav.receive',
      'nav.payloadRules',
      'nav.events',
      'nav.deliveries',
      'nav.analytics',
      'nav.develop',
    ]);
  });

  it('files sending under Send: connections, consumers and the event types\' schemas', () => {
    expect(tabKeys('nav.send')).toEqual(['nav.connections', 'nav.consumers', 'nav.schemas']);
  });

  it('keeps the raw endpoint and subscription tables out of the strip, lighting Connections on them', () => {
    const connections = section('nav.send').tabs[0];
    expect(tabKeys('nav.send')).not.toContain('nav.endpoints');
    expect(tabKeys('nav.send')).not.toContain('nav.subscriptions');
    expect(sectionFor(`${P}/endpoints`)).toBe(section('nav.send'));
    expect(sectionFor(`${P}/subscriptions`)).toBe(section('nav.send'));
    expect(connections.owns).toEqual(expect.arrayContaining(['connections', 'endpoints', 'subscriptions']));
  });

  it('still offers the raw tables somewhere a search finds them', () => {
    expect(section('nav.send').more?.map((entry) => entry.nameKey)).toEqual(['nav.endpoints', 'nav.subscriptions']);
  });

  it('files receiving under Receive, where a source holds its own destinations', () => {
    expect(tabKeys('nav.receive')).toEqual(['nav.incomingSources']);
    expect(sectionFor(`${P}/incoming-sources/source-1`)).toBe(section('nav.receive'));
  });

  it('gathers what changes a payload under Payload rules, workflows demoted there from the rail', () => {
    expect(tabKeys('nav.payloadRules')).toEqual(['nav.transformations', 'nav.rules', 'nav.piiRules', 'nav.workflows']);
    expect(sectionFor(`${P}/workflows/wf-1`)).toBe(section('nav.payloadRules'));
  });

  it('opens the transform studio as the Transformations editor, not as a Develop tab', () => {
    expect(tabKeys('nav.develop')).not.toContain('nav.transformStudio');
    expect(sectionFor(`${P}/transformations/studio`)).toBe(section('nav.payloadRules'));
  });

  it('names usage for what it shows: the plan\'s limits', () => {
    expect(tabKeys('nav.analytics')).toContain('nav.usage');
    expect(en.nav.usage).toBe('Plan limits');
    expect(uk.nav.usage).toBe('Ліміти плану');
  });

  it('gives the project its own settings tab, beside its API keys', () => {
    const keys = SETTINGS_SECTION.tabs.map((tab) => tab.nameKey);
    expect(keys).toContain('nav.projectSettings');
    expect(keys.indexOf('nav.projectSettings')).toBe(keys.indexOf('nav.apiKeys') - 1);
    expect(sectionFor(`${P}/project-settings`)).toBe(SETTINGS_SECTION);
  });
});

/** A bookmark, a link in an email or a doc page must not break because the menu moved. */
describe('every URL the old menu led to', () => {
  const OLD = [
    'connections', 'connection-setup', 'endpoints', 'consumers', 'subscriptions', 'incoming-sources',
    'incoming-sources/source-1', 'transformations', 'rules', 'schemas', 'pii-rules', 'events',
    'events/event-1', 'incoming-events', 'deliveries', 'dlq', 'incoming-dlq', 'replay', 'workflows',
    'workflows/wf-1', 'analytics', 'alerts', 'incidents', 'usage', 'test-console', 'transform-studio',
    'event-diff', 'test-endpoints', 'api-keys',
  ];

  it.each(OLD)('%s still resolves to a page of its own', (segment) => {
    const matches = matchRoutes(router.routes, `${P}/${segment}`) ?? [];
    expect(matches[matches.length - 1]?.route.path).not.toBe('*');
  });

  it('opens a new endpoint detail page at endpoints/:endpointId', () => {
    const matches = matchRoutes(router.routes, `${P}/endpoints/endpoint-1`) ?? [];
    expect(matches[matches.length - 1]?.route.path).toBe('projects/:projectId/endpoints/:endpointId');
  });
});
