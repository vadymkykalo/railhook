import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, screen } from '@testing-library/react';
import '../../i18n';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { DeliveryResponse } from '../../types/api.types';

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() },
}));
vi.mock('../../api/deliveries.api', () => ({
  deliveriesApi: {
    get: vi.fn(), getAttempts: vi.fn(), replay: vi.fn(), replayFromAttempt: vi.fn(), dryRunReplay: vi.fn(),
  },
}));

import { toast } from 'sonner';
import DeliveryDetailsSheet from '../DeliveryDetailsSheet';
import { deliveriesApi } from '../../api/deliveries.api';

const PENDING: DeliveryResponse = {
  id: 'delivery-1', eventId: 'event-1', endpointId: 'endpoint-1', subscriptionId: 'sub-1',
  status: 'PENDING', attemptCount: 0, maxAttempts: 5, createdAt: new Date().toISOString(),
};

function renderSheet() {
  return renderPage(
    <DeliveryDetailsSheet deliveryId="delivery-1" open onClose={() => {}} onRefresh={() => {}} />,
    { path: '/projects/:projectId/deliveries', initialEntry: `/projects/${TEST_PROJECT_ID}/deliveries` },
  );
}

const tick = () => act(async () => { vi.advanceTimersByTime(3_100); });

describe('DeliveryDetailsSheet auto-refresh', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.mocked(deliveriesApi.getAttempts).mockResolvedValue([]);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps the delivery on screen while a background refresh is in flight', async () => {
    vi.mocked(deliveriesApi.get)
      .mockResolvedValueOnce(PENDING)
      .mockReturnValue(new Promise(() => {}));
    renderSheet();
    await screen.findByText(/queued for first attempt/i);

    await tick();

    expect(deliveriesApi.get).toHaveBeenCalledTimes(2);
    expect(screen.getByText(/queued for first attempt/i)).toBeInTheDocument();
    expect(document.querySelector('[role="dialog"] .animate-pulse')).toBeNull();
  });

  it('reports a refresh that keeps failing once, not every three seconds', async () => {
    vi.mocked(deliveriesApi.get)
      .mockResolvedValueOnce(PENDING)
      .mockRejectedValue({ response: { status: 500 } });
    renderSheet();
    await screen.findByText(/queued for first attempt/i);

    await tick();
    await tick();
    await tick();

    expect(deliveriesApi.get).toHaveBeenCalledTimes(4);
    expect(toast.error).toHaveBeenCalledTimes(1);
  });
});
