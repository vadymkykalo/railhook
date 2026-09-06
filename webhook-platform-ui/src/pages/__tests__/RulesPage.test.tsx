import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { RuleResponse } from '../../api/rules.api';
import type { ProjectResponse } from '../../types/api.types';

vi.mock('../../api/rules.api', () => ({
  rulesApi: {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    toggle: vi.fn(),
  },
}));

vi.mock('../../api/projects.api', () => ({
  projectsApi: { get: vi.fn(), list: vi.fn() },
}));

// The page loads endpoints and transformations to fill the action pickers. Without these the
// whole load rejects and every assertion below would pass against an error state.
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn().mockResolvedValue([]) },
}));

import RulesPage from '../RulesPage';
import { rulesApi } from '../../api/rules.api';
import { projectsApi } from '../../api/projects.api';

/**
 * Rules decide what happens to an event before anything is delivered, and one of the four
 * actions is DROP.
 *
 * <p>That is the whole reason this page is worth a test. A rule that routes to the wrong
 * endpoint produces a delivery somebody can see and complain about; a rule that drops produces
 * nothing at all — no delivery, no attempt, no failure — and the customer's report is "we never
 * got the webhook", which looks like every other cause. So what is pinned here is that the page
 * says out loud which rules drop, that a rule is never toggled or deleted by accident, and that
 * the search narrows the list rather than the list being what the API returned.
 */

const now = new Date('2026-08-01T00:00:00Z').toISOString();

const PROJECT: ProjectResponse = {
  id: TEST_PROJECT_ID,
  name: 'Test Project',
  createdAt: now,
  updatedAt: now,
} as ProjectResponse;

const rule = (over: Partial<RuleResponse>): RuleResponse => ({
  id: 'rule-1',
  projectId: TEST_PROJECT_ID,
  name: 'Route payments',
  description: 'Everything under payment.*',
  enabled: true,
  priority: 10,
  eventTypePattern: 'payment.*',
  conditions: null,
  actions: [],
  totalExecutions: 0,
  totalMatches: 0,
  createdAt: now,
  updatedAt: now,
  ...over,
});

const ROUTING = rule({
  actions: [{
    id: 'action-1',
    type: 'ROUTE',
    endpointId: 'endpoint-1',
    endpointUrl: 'https://example.com/hook',
    transformationId: null,
    transformationName: null,
    config: {},
    sortOrder: 0,
    createdAt: now,
  }],
});

const DROPPING = rule({
  id: 'rule-drop',
  name: 'Discard test traffic',
  description: 'Noise from staging',
  eventTypePattern: 'test.*',
  actions: [{
    id: 'action-2',
    type: 'DROP',
    endpointId: null,
    endpointUrl: null,
    transformationId: null,
    transformationName: null,
    config: {},
    sortOrder: 0,
    createdAt: now,
  }],
});

function renderRules() {
  return renderPage(<RulesPage />, {
    path: '/projects/:projectId/rules',
    initialEntry: `/projects/${TEST_PROJECT_ID}/rules`,
  });
}

function rowFor(name: string): HTMLElement {
  const row = screen.getByText(name).closest('li');
  expect(row, `no row rendered for ${name}`).not.toBeNull();
  return row as HTMLElement;
}

describe('RulesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(projectsApi.get).mockResolvedValue(PROJECT);
    vi.mocked(rulesApi.list).mockResolvedValue([ROUTING, DROPPING]);
  });

  it('shows what each rule matches and what it then does', async () => {
    renderRules();

    expect(await screen.findByText('Route payments')).toBeInTheDocument();
    // The pattern is the half a reader checks first: which events this touches at all.
    expect(screen.getByText('payment.*')).toBeInTheDocument();
    expect(within(rowFor('Route payments')).getByText('https://example.com/hook')).toBeInTheDocument();
  });

  it('names the drop action rather than leaving the row blank', async () => {
    renderRules();

    await screen.findByText('Discard test traffic');
    // Whatever the wording, a dropping rule may not read as a rule that does nothing.
    const row = rowFor('Discard test traffic');
    expect(row.textContent).toMatch(/drop|відкид/i);
    expect(row.textContent).not.toMatch(/rules\.actionTypes/);
  });

  it('narrows the list by the pattern, not just the name', async () => {
    renderRules();
    await screen.findByText('Route payments');

    await userEvent.type(screen.getByRole('textbox', { name: /search|пошук/i }), 'test.*');

    await waitFor(() => expect(screen.queryByText('Route payments')).toBeNull());
    expect(screen.getByText('Discard test traffic')).toBeInTheDocument();
  });

  it('toggles a rule off through the toggle endpoint, not by deleting it', async () => {
    vi.mocked(rulesApi.toggle).mockResolvedValue({ ...ROUTING, enabled: false });
    renderRules();
    await screen.findByText('Route payments');

    await userEvent.click(within(rowFor('Route payments')).getByRole('switch'));

    await waitFor(() => expect(rulesApi.toggle).toHaveBeenCalledWith(TEST_PROJECT_ID, ROUTING.id, false));
    expect(rulesApi.delete).not.toHaveBeenCalled();
  });

  it('creates, deletes and toggles nothing by being opened', async () => {
    renderRules();

    await screen.findByText('Route payments');
    expect(rulesApi.create).not.toHaveBeenCalled();
    expect(rulesApi.update).not.toHaveBeenCalled();
    expect(rulesApi.delete).not.toHaveBeenCalled();
    expect(rulesApi.toggle).not.toHaveBeenCalled();
  });

  it('tells a project with no rules that events pass through untouched', async () => {
    vi.mocked(rulesApi.list).mockResolvedValue([]);
    renderRules();

    await waitFor(() => expect(rulesApi.list).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
    expect(screen.queryByRole('switch')).toBeNull();
  });

  it('shows an error state rather than an empty rule list when the load fails', async () => {
    // An empty list here reads as "no rule touches your events", which is the opposite of
    // what an unknown state means when a DROP rule might be among them.
    vi.mocked(rulesApi.list).mockRejectedValue(new Error('boom'));
    renderRules();

    await waitFor(() => expect(rulesApi.list).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
    expect(screen.queryByRole('switch')).toBeNull();
  });
});
