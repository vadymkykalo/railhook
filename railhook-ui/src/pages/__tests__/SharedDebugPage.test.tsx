import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';

vi.mock('../../api/debugLinks.api', () => ({ debugLinksApi: { viewPublic: vi.fn() } }));

import SharedDebugPage from '../SharedDebugPage';
import { debugLinksApi } from '../../api/debugLinks.api';

describe('SharedDebugPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows the event, not the old error, when a retry succeeds', async () => {
    vi.mocked(debugLinksApi.viewPublic)
      .mockRejectedValueOnce({ response: { status: 500 } })
      .mockResolvedValueOnce({
        eventType: 'order.created',
        sanitizedPayload: '{"a":1}',
        eventCreatedAt: new Date().toISOString(),
        linkExpiresAt: new Date().toISOString(),
        projectName: 'Shop',
      });
    renderPage(<SharedDebugPage />, { path: '/shared/debug/:token', initialEntry: '/shared/debug/tok' });

    fireEvent.click(await screen.findByRole('button', { name: /retry/i }));

    expect(await screen.findByText('order.created')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument();
  });
});
