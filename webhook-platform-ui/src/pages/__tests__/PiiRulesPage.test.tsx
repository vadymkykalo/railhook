import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { PiiMaskingRuleResponse } from '../../api/piiRules.api';

vi.mock('../../api/piiRules.api', () => ({
  piiRulesApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    seedDefaults: vi.fn(),
    preview: vi.fn(),
  },
}));

import PiiRulesPage from '../PiiRulesPage';
import { piiRulesApi } from '../../api/piiRules.api';

/**
 * The rules that keep a customer's personal data out of stored payloads.
 *
 * <p>Everything on this page fails in the same direction, which is why it is worth testing
 * rather than eyeballing: a masking rule that stops applying does not throw, does not turn a
 * screen red and does not appear in any metric. It just means the next event's payload is
 * retained in full, and nobody finds out until somebody reads a stored body.
 *
 * <p>So the three things held down are the three ways that happens by accident — a built-in
 * rule deleted, any rule deleted without agreeing to it, and a disabled rule that looks like
 * an enabled one.
 */

const now = new Date('2026-08-01T00:00:00Z').toISOString();

const BUILTIN: PiiMaskingRuleResponse = {
  id: 'rule-builtin',
  projectId: TEST_PROJECT_ID,
  ruleType: 'BUILTIN',
  patternName: 'email',
  maskStyle: 'PARTIAL',
  enabled: true,
  createdAt: now,
  updatedAt: now,
};

const CUSTOM: PiiMaskingRuleResponse = {
  id: 'rule-custom',
  projectId: TEST_PROJECT_ID,
  ruleType: 'CUSTOM',
  patternName: 'ssn',
  jsonPath: '$.user.ssn',
  maskStyle: 'FULL',
  enabled: true,
  createdAt: now,
  updatedAt: now,
};

const DISABLED: PiiMaskingRuleResponse = { ...CUSTOM, id: 'rule-off', patternName: 'phone', enabled: false };

function renderPii() {
  return renderPage(<PiiRulesPage />, {
    path: '/projects/:projectId/pii-rules',
    initialEntry: `/projects/${TEST_PROJECT_ID}/pii-rules`,
  });
}

/** The row a rule renders into, found by the pattern name that is unique to it. */
function rowFor(patternName: string): HTMLElement {
  const name = screen.getByText(patternName);
  const row = name.closest('li');
  expect(row, `no row rendered for ${patternName}`).not.toBeNull();
  return row as HTMLElement;
}

describe('PiiRulesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(piiRulesApi.list).mockResolvedValue([BUILTIN, CUSTOM]);
  });

  it('lists the rules with the field each one matches', async () => {
    renderPii();

    expect(await screen.findByText('ssn')).toBeInTheDocument();
    expect(screen.getByText('$.user.ssn')).toBeInTheDocument();
  });

  it('offers no delete on a built-in rule', async () => {
    renderPii();
    await screen.findByText('email');

    // The built-ins are the defaults that cover the common fields. Removing one is not an
    // edit somebody meant to make, so the control is not there to click.
    expect(within(rowFor('email')).queryByRole('button', { name: /delete|видалити/i })).toBeNull();
    expect(within(rowFor('ssn')).getByRole('button', { name: /delete|видалити/i })).toBeInTheDocument();
  });

  it('deletes nothing until the dialog is agreed to', async () => {
    renderPii();
    await screen.findByText('ssn');

    await userEvent.click(within(rowFor('ssn')).getByRole('button', { name: /delete|видалити/i }));

    // The dialog is open. Until its action is clicked, the rule still applies.
    // (Role is `dialog`: ui/alert-dialog.tsx is built on @radix-ui/react-dialog, so the
    // destructive-confirmation semantics are the shape, not the announced role.)
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(piiRulesApi.delete).not.toHaveBeenCalled();
  });

  it('shows a disabled rule as disabled', async () => {
    // A rule that is present but off masks nothing, and is the one state where the page
    // looking right and the data being wrong coincide.
    vi.mocked(piiRulesApi.list).mockResolvedValue([DISABLED]);
    renderPii();

    await screen.findByText('phone');
    const toggle = within(rowFor('phone')).getByRole('switch');
    expect(toggle).not.toBeChecked();
  });

  it('turning a rule off updates it rather than removing it', async () => {
    vi.mocked(piiRulesApi.update).mockResolvedValue({ ...CUSTOM, enabled: false });
    renderPii();
    await screen.findByText('ssn');

    await userEvent.click(within(rowFor('ssn')).getByRole('switch'));

    await waitFor(() => expect(piiRulesApi.update).toHaveBeenCalledWith(
      TEST_PROJECT_ID,
      CUSTOM.id,
      expect.objectContaining({ enabled: false }),
    ));
    expect(piiRulesApi.delete).not.toHaveBeenCalled();
  });

  it('offers the defaults to a project that has no rules yet', async () => {
    vi.mocked(piiRulesApi.list).mockResolvedValue([]);
    renderPii();

    await waitFor(() => expect(piiRulesApi.list).toHaveBeenCalled());
    // A project with no masking at all is the state worth getting out of in one click.
    expect(await screen.findByRole('button', { name: /default|стандарт|типов/i })).toBeInTheDocument();
    expect(piiRulesApi.seedDefaults).not.toHaveBeenCalled();
  });

  it('shows an error state rather than an empty page when the rules fail to load', async () => {
    // An empty page here reads as "this project masks nothing", which is a different and
    // much worse statement than "we could not load the rules".
    vi.mocked(piiRulesApi.list).mockRejectedValue(new Error('boom'));
    renderPii();

    await waitFor(() => expect(piiRulesApi.list).toHaveBeenCalled());
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
    expect(screen.queryByRole('switch')).toBeNull();
  });

  it('changes nothing by being opened', async () => {
    renderPii();

    await screen.findByText('ssn');
    expect(piiRulesApi.create).not.toHaveBeenCalled();
    expect(piiRulesApi.update).not.toHaveBeenCalled();
    expect(piiRulesApi.delete).not.toHaveBeenCalled();
    expect(piiRulesApi.seedDefaults).not.toHaveBeenCalled();
  });
});
