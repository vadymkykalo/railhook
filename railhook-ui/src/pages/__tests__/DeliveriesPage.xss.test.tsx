import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { ProjectResponse, EndpointResponse } from '../../types/api.types';

// Regression test: DeliveriesPage used to render
// `t('deliveries.subtitle', { project: project.name })` through the React
// prop that injects a string as raw HTML (`{{ __html: ... }}`). i18next is
// configured with `interpolation: { escapeValue: false }` (correct for
// normal React rendering, since React already escapes text children) — but
// that setting turns into stored XSS the moment the interpolated, translated
// string is piped into raw HTML instead: a project named
// `<img src=x onerror=alert(1)>` got injected as a live <img> tag whose
// onerror handler fired.
//
// The fix replaces every such site with react-i18next's <Trans> component,
// which renders markup as real React elements and escapes interpolated
// values. This test proves the hostile name renders as inert literal text —
// no <img> element is created, no script runs — through the DeliveriesPage
// subtitle, which is a <Trans i18nKey="deliveries.subtitle" ...> use.
//
// Revert the DeliveriesPage.tsx subtitle back to the raw-HTML-injection prop
// (see git history for the exact pre-fix line) to see this test fail (an
// <img> element appears in the DOM).

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn(), listPaged: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: {
    listByProject: vi.fn(),
    get: vi.fn(),
    replay: vi.fn(),
    dryRunReplay: vi.fn(),
    replayFromAttempt: vi.fn(),
    bulkReplay: vi.fn(),
    getAttempts: vi.fn(),
  },
}));
vi.mock('../../api/events.api', () => ({
  eventsApi: { get: vi.fn(), listByProject: vi.fn(), sendTestEvent: vi.fn() },
}));

import DeliveriesPage from '../DeliveriesPage';
import { projectsApi } from '../../api/projects.api';
import { endpointsApi } from '../../api/endpoints.api';
import { deliveriesApi } from '../../api/deliveries.api';

const HOSTILE_NAME = '<img src=x onerror=alert(1)>';

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: HOSTILE_NAME,
  schemaValidationEnabled: false,
  schemaValidationPolicy: 'WARN',
  idempotencyPolicy: 'NONE',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

const ENDPOINT: EndpointResponse = {
  id: 'endpoint-1',
  projectId: TEST_PROJECT_ID,
  url: 'https://example.com/webhook',
  enabled: true,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

function emptyPage() {
  return { content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 } as any;
}

function renderDeliveries() {
  return renderPage(<DeliveriesPage />, {
    path: '/projects/:projectId/deliveries',
    initialEntry: `/projects/${TEST_PROJECT_ID}/deliveries`,
  });
}

describe('DeliveriesPage XSS', () => {
  let alertSpy: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(endpointsApi.list).mockResolvedValue([ENDPOINT]);
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(deliveriesApi.listByProject).mockResolvedValue(emptyPage());
    alertSpy = vi.fn();
    window.alert = alertSpy as unknown as typeof window.alert;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('canonical case: a hostile project name does not execute as script', async () => {
    const { container } = renderDeliveries();

    // Wait for the page (and its <Trans> subtitle, which interpolates
    // project.name) to finish rendering.
    await screen.findByText(/onerror=alert/);

    // If this were still piped through the raw-HTML-injection prop, the
    // browser would have parsed the <img> tag and immediately fired its
    // onerror handler (invalid src="x").
    expect(container.querySelector('img')).toBeNull();
    expect(alertSpy).not.toHaveBeenCalled();
  });

  it('the hostile name pushed through the <Trans> subtitle renders as literal text', async () => {
    renderDeliveries();

    // The subtitle is `deliveries.subtitle` = 'Track webhook delivery
    // attempts for <strong>{{project}}</strong>' rendered via <Trans>. The
    // hostile project name must show up verbatim as text content — not be
    // interpreted as markup — inside the <strong> Trans produces.
    const strongEl = await screen.findByText(/onerror=alert/);
    expect(strongEl).toBeInTheDocument();
    expect(strongEl.tagName.toLowerCase()).toBe('strong');
    // <Trans> renders the interpolated value as inert text — the whole
    // hostile string is visible verbatim (the literal `<` is escaped to the
    // three characters `&lt;` inside the text node rather than being parsed
    // as a tag start), never as a live <img> element.
    expect(strongEl.textContent).toContain('img src=x onerror=alert(1)>');
    expect(strongEl.querySelector('img')).toBeNull();
    expect(document.querySelectorAll('img').length).toBe(0);
    expect(alertSpy).not.toHaveBeenCalled();
  });
});
