import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, screen, within } from '@testing-library/react';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { DeliveryAttemptResponse, DeliveryResponse } from '../../types/api.types';

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

const DLQ_DELIVERY: DeliveryResponse = {
  id: 'delivery-9f3c2a1b', eventId: 'event-1', endpointId: 'endpoint-1', subscriptionId: 'sub-1',
  status: 'DLQ', attemptCount: 2, maxAttempts: 2, createdAt: new Date().toISOString(),
};

const FAILED_ATTEMPTS: DeliveryAttemptResponse[] = [1, 2].map((n) => ({
  id: `attempt-${n}`, deliveryId: DLQ_DELIVERY.id, attemptNumber: n, httpStatusCode: 503,
  responseBody: n === 2 ? '{"error":"maintenance window"}' : '{"error":"busy"}',
  durationMs: 120, createdAt: new Date(Date.now() - (3 - n) * 60_000).toISOString(),
}));

const follows = (earlier: Element, later: Element) =>
  (earlier.compareDocumentPosition(later) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

/**
 * Someone opens a failed delivery to learn why it failed and to send it again. The sheet used
 * to open on four ids and a progress card, with the diagnosis below them and the replay button
 * under every attempt's headers and bodies.
 */
describe('DeliveryDetailsSheet reading order', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(deliveriesApi.get).mockResolvedValue(DLQ_DELIVERY);
    vi.mocked(deliveriesApi.getAttempts).mockResolvedValue(FAILED_ATTEMPTS);
  });

  it('puts the diagnosis first, then the last response, then the attempts', async () => {
    renderSheet();
    const diagnosis = await screen.findByRole('heading', { name: en.deliveryDetails.diagnosis.title });
    const lastResponse = screen.getByRole('heading', { name: en.deliveryDetails.lastResponse });
    const attempts = screen.getByRole('heading', { name: en.deliveryDetails.deliveryAttempts });

    expect(follows(diagnosis, lastResponse)).toBe(true);
    expect(follows(lastResponse, attempts)).toBe(true);
    expect(within(lastResponse.closest('section')!).getByText(/maintenance window/)).toBeInTheDocument();
  });

  it('keeps Replay in a footer that stays in view', async () => {
    renderSheet();
    const replay = await screen.findByRole('button', { name: en.deliveryDetails.replayDelivery });
    expect(replay.closest('[data-sheet-footer]')).toHaveClass('sticky');
  });

  it('files the ids under a Trace section that starts closed', async () => {
    renderSheet();
    const trace = (await screen.findByText(en.deliveryDetails.trace)).closest('details')!;
    expect(trace).not.toHaveAttribute('open');
    expect(within(trace).getByText(DLQ_DELIVERY.id)).toBeInTheDocument();
  });
});
