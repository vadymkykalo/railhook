import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import '../../i18n';
import { renderPage } from '../../test/renderPage';
import type {
  InvoiceResponse,
  OrganizationBillingResponse,
  PlanResponse,
  UsageResponse,
} from '../../api/billing.api';

vi.mock('../../api/billing.api', () => ({
  billingApi: {
    listPlans: vi.fn(),
    getOrganizationBilling: vi.fn(),
    updateBillingInfo: vi.fn(),
    changePlan: vi.fn(),
    getUsage: vi.fn(),
    listInvoices: vi.fn(),
    createCheckout: vi.fn(),
    createPortal: vi.fn(),
    cancelSubscription: vi.fn(),
  },
}));

import BillingPage from '../BillingPage';
import { billingApi } from '../../api/billing.api';

/**
 * The page that decides what a customer is charged, which shipped with no test at all.
 *
 * <p>Two things it has to get right and would fail silently at. The first is `-1`, which is how
 * every limit in the plan catalog spells "unlimited" — rendered as a number it reads as a plan
 * that allows minus one project, and the enterprise tier is nothing but those. The second is the
 * self-hosted case, where every limit is -1 and the whole page is that one rule.
 */

const FREE: PlanResponse = {
  id: 'plan-free',
  name: 'free',
  displayName: 'Free',
  maxEventsPerMonth: 10000,
  maxEndpointsPerProject: 5,
  maxProjects: 3,
  maxMembers: 5,
  maxActiveTunnels: 1,
  rateLimitPerSecond: 10,
  maxRetentionDays: 7,
  features: { rules: false, replay: false },
  priceMonthlyCents: 0,
  priceYearlyCents: 0,
};

const ENTERPRISE: PlanResponse = {
  ...FREE,
  id: 'plan-enterprise',
  name: 'enterprise',
  displayName: 'Enterprise',
  maxEventsPerMonth: -1,
  maxEndpointsPerProject: -1,
  maxProjects: -1,
  maxMembers: -1,
  maxActiveTunnels: -1,
  rateLimitPerSecond: -1,
  maxRetentionDays: -1,
  features: { rules: true, replay: true },
  priceMonthlyCents: -1,
  priceYearlyCents: -1,
};

const use = (current: number, limit: number) => ({
  current,
  limit,
  percentUsed: limit > 0 ? Math.round((current / limit) * 100) : 0,
});

const USAGE: UsageResponse = {
  events: use(2500, 10000),
  endpoints: use(2, 5),
  projects: use(1, 3),
  members: use(1, 5),
  rateLimitPerSecond: 10,
  retentionDays: 7,
  periodStart: new Date('2026-08-01T00:00:00Z').toISOString(),
  periodEnd: new Date('2026-09-01T00:00:00Z').toISOString(),
};

const BILLING = {
  organizationId: 'org-1',
  plan: FREE,
  billingStatus: 'ACTIVE',
  billingEmail: 'billing@example.com',
  usage: USAGE,
} as unknown as OrganizationBillingResponse;

const INVOICE: InvoiceResponse = {
  id: 'inv-1',
  status: 'PAID',
  amountCents: 2900,
  currency: 'USD',
  planName: 'starter',
  periodStart: new Date('2026-08-01T00:00:00Z').toISOString(),
  periodEnd: new Date('2026-09-01T00:00:00Z').toISOString(),
  paidAt: new Date('2026-08-02T00:00:00Z').toISOString(),
  invoiceUrl: null,
};

function renderBilling() {
  return renderPage(<BillingPage />, { path: '/billing', initialEntry: '/billing' });
}

describe('BillingPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(billingApi.getOrganizationBilling).mockResolvedValue(BILLING);
    vi.mocked(billingApi.getUsage).mockResolvedValue(USAGE);
    vi.mocked(billingApi.listPlans).mockResolvedValue([FREE, ENTERPRISE]);
    vi.mocked(billingApi.listInvoices).mockResolvedValue([]);
  });

  it('shows the plan the organization is on', async () => {
    renderBilling();

    // The name appears on the current-plan card and again in the catalog row.
    expect(await screen.findAllByText(/free/i)).not.toHaveLength(0);
  });

  it('renders an unlimited plan as unlimited, not as -1', async () => {
    // Every limit in the enterprise row is -1. Printed as a number it reads as a plan that
    // allows minus one project, and it is the whole of the self-hosted plan as well.
    renderBilling();

    await screen.findByText(/enterprise/i);
    expect(await screen.findAllByText(/unlimited/i)).not.toHaveLength(0);
    expect(screen.queryByText('-1')).toBeNull();
  });

  it('shows usage against the limit', async () => {
    renderBilling();

    // 2,500 of 10,000 — the number a customer checks before they are cut off.
    await waitFor(() => expect(billingApi.getUsage).toHaveBeenCalled());
    expect(await screen.findByText(/2[,.\s]?500/)).toBeInTheDocument();
  });

  it('lists invoices when there are any', async () => {
    vi.mocked(billingApi.listInvoices).mockResolvedValue([INVOICE]);
    renderBilling();

    await waitFor(() => expect(billingApi.listInvoices).toHaveBeenCalled());
    // Matched across elements: the amount is formatted with its own currency markup.
    await waitFor(() =>
      expect(document.body.textContent).toMatch(/29[.,]00|\$29|2900/));
  });

  it('survives an organization whose billing call fails', async () => {
    // A self-hosted deployment with BILLING_ENABLED=false is the common case, and this page
    // must not be a white screen there.
    vi.mocked(billingApi.getOrganizationBilling).mockRejectedValue(new Error('billing disabled'));
    renderBilling();

    // Something has to be on screen — an error state or a skeleton. A blank page is the
    // failure mode this guards, and a query that has not settled yet is not it.
    await waitFor(() => expect(document.body.textContent?.trim()).not.toBe(''));
  });

  it('does not charge anyone by rendering', async () => {
    // Nothing on this page may start a checkout or cancel a subscription without a click.
    renderBilling();

    await screen.findAllByText(/free/i);
    expect(billingApi.createCheckout).not.toHaveBeenCalled();
    expect(billingApi.changePlan).not.toHaveBeenCalled();
    expect(billingApi.cancelSubscription).not.toHaveBeenCalled();
    expect(billingApi.createPortal).not.toHaveBeenCalled();
  });
});
