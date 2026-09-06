import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { IncidentResponse } from '../../api/incidents.api';

vi.mock('../../api/incidents.api', () => ({
  incidentsApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    addTimeline: vi.fn(),
    countOpen: vi.fn(),
  },
}));

import IncidentsPage from '../IncidentsPage';
import { incidentsApi } from '../../api/incidents.api';

/**
 * The three numbers above the incident list, and the one thing they must agree about.
 *
 * <p>They are three tiles in a row, so a reader takes them as three answers about the same
 * thing: this project. Only one of them was. "Open" came from a server count; "Investigating"
 * and "Critical" were `incidents.filter(...)` over the rows on screen — one page of a filtered,
 * paginated list. A project with more open incidents than fit on a page therefore read
 * "Critical: 0" with a critical incident open on page two, and nothing about the screen
 * suggested the number was partial.
 *
 * <p>The test that catches that is the one where the list and the counts disagree, because
 * that is the situation the old code could not represent.
 */

const now = new Date('2026-08-01T00:00:00Z').toISOString();

const incident = (over: Partial<IncidentResponse>): IncidentResponse => ({
  id: 'incident-1',
  projectId: TEST_PROJECT_ID,
  title: 'Checkout webhooks failing',
  status: 'OPEN',
  severity: 'WARNING',
  rcaNotes: null,
  resolvedAt: null,
  createdAt: now,
  updatedAt: now,
  timeline: null,
  ...over,
});

const page = (content: IncidentResponse[], totalElements = content.length) => ({
  content,
  totalElements,
  totalPages: Math.max(1, Math.ceil(totalElements / 20)),
  size: 20,
  number: 0,
  first: true,
  last: totalElements <= 20,
});

function renderIncidents() {
  return renderPage(<IncidentsPage />, {
    path: '/projects/:projectId/incidents',
    initialEntry: `/projects/${TEST_PROJECT_ID}/incidents`,
  });
}

/** The number a tile shows, read off the tile carrying the given label. */
function tileValue(label: RegExp): string | undefined {
  const heading = screen.getAllByText(label)[0];
  return heading?.closest('div')?.parentElement?.querySelector('p')?.textContent ?? undefined;
}

describe('IncidentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(incidentsApi.list).mockResolvedValue(page([incident({})]));
    vi.mocked(incidentsApi.countOpen).mockResolvedValue({ count: 1, investigating: 0, critical: 0 });
  });

  it('lists the incidents', async () => {
    renderIncidents();

    expect(await screen.findByText('Checkout webhooks failing')).toBeInTheDocument();
  });

  it('counts incidents the list has not loaded', async () => {
    // 24 unresolved, of which one critical and two being investigated — and the page shows
    // the first twenty ordinary ones. Every tile here is a number the rows on screen cannot
    // produce, which is exactly the case the old page got wrong.
    vi.mocked(incidentsApi.list).mockResolvedValue(
      page(Array.from({ length: 20 }, (_, i) => incident({ id: `incident-${i}`, title: `Routine ${i}` })), 24),
    );
    vi.mocked(incidentsApi.countOpen).mockResolvedValue({ count: 24, investigating: 2, critical: 1 });

    renderIncidents();

    await screen.findByText('Routine 0');
    await waitFor(() => expect(tileValue(/investigat|розсліду/i)).toBe('2'));
    expect(tileValue(/critical|критич/i)).toBe('1');
    expect(tileValue(/^open$|відкрит/i)).toBe('24');
  });

  it('says all clear only when nothing is unresolved', async () => {
    vi.mocked(incidentsApi.list).mockResolvedValue(page([]));
    vi.mocked(incidentsApi.countOpen).mockResolvedValue({ count: 0, investigating: 0, critical: 0 });

    renderIncidents();

    await waitFor(() => expect(incidentsApi.countOpen).toHaveBeenCalled());
    await waitFor(() => expect(tileValue(/^open$|відкрит/i)).toBe('0'));
    expect(document.body.textContent).not.toMatch(/incidents\.tiles/);
  });

  it('renders the tiles at zero rather than blank while the counts are still loading', async () => {
    // A tile with no number in it reads as a broken tile. Until the count lands, zero is the
    // honest placeholder and the one the page already falls back to.
    vi.mocked(incidentsApi.countOpen).mockImplementation(() => new Promise(() => {}));

    renderIncidents();

    await screen.findByText('Checkout webhooks failing');
    expect(tileValue(/critical|критич/i)).toBe('0');
  });

  it('creates and updates nothing by being opened', async () => {
    renderIncidents();

    await screen.findByText('Checkout webhooks failing');
    expect(incidentsApi.create).not.toHaveBeenCalled();
    expect(incidentsApi.update).not.toHaveBeenCalled();
    expect(incidentsApi.addTimeline).not.toHaveBeenCalled();
  });

  it('renders something when the incident list fails to load', async () => {
    vi.mocked(incidentsApi.list).mockRejectedValue(new Error('boom'));
    renderIncidents();

    await waitFor(() => expect(incidentsApi.list).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
  });
});
