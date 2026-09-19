import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';
import type { PublicBin } from '../../api/publicBin.api';

vi.mock('../../api/publicBin.api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/publicBin.api')>()),
  publicBinApi: { create: vi.fn(), get: vi.fn() },
}));

import TesterPage, { STORAGE_KEY } from '../TesterPage';
import { publicBinApi } from '../../api/publicBin.api';

/**
 * The webhook tester on the public site. A developer makes a URL, points a provider at it and
 * sees exactly what arrived — with the way to keep, retry and forward those requests one sign-up
 * away. A crawler, and the prerender, must never make a URL just by loading the page.
 */
const SLUG = 'abcdefghijklmnopqrstuvwx';
const URL = `https://railhook.io/hook/p/${SLUG}`;

const EMPTY: PublicBin = {
  slug: SLUG, url: URL, expiresAt: '2026-09-19T12:00:00Z', requestCount: 0, requests: [],
};

const WITH_REQUESTS: PublicBin = {
  ...EMPTY,
  requestCount: 2,
  requests: [
    {
      id: 2, method: 'PUT', query: null, headers: { 'Content-Type': 'text/plain' }, body: 'second',
      bodyTruncated: false, sizeBytes: 6, contentType: 'text/plain', sourceIp: '203.0.113.9',
      receivedAt: '2026-09-18T12:00:02Z',
    },
    {
      id: 1, method: 'POST', query: 'attempt=1',
      headers: { 'Stripe-Signature': '***MASKED***', 'Content-Type': 'application/json' },
      body: '{"type":"invoice.paid"}', bodyTruncated: false, sizeBytes: 23, contentType: 'application/json',
      sourceIp: '203.0.113.9', receivedAt: '2026-09-18T12:00:01Z',
    },
  ],
};

function renderTester() {
  return renderPage(<TesterPage />, {
    path: '/tester', initialEntry: '/tester', auth: { user: null, token: null, isAuthenticated: false },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  window.__RAILHOOK__ = { publicTester: true };
});

afterEach(() => {
  localStorage.clear();
  delete window.__RAILHOOK__;
});

describe('TesterPage', () => {
  it('names itself for search, and makes no URL just by being loaded', async () => {
    renderTester();
    expect(document.title).toBe(en.meta.tester.title);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(en.tester.title);
    expect(screen.getByRole('button', { name: en.tester.create })).toBeInTheDocument();
    expect(publicBinApi.create).not.toHaveBeenCalled();
    expect(publicBinApi.get).not.toHaveBeenCalled();
  });

  it('makes a URL on request, shows it with a command to try it, and remembers it', async () => {
    vi.mocked(publicBinApi.create).mockResolvedValue(EMPTY);
    vi.mocked(publicBinApi.get).mockResolvedValue(EMPTY);
    renderTester();

    await userEvent.click(screen.getByRole('button', { name: en.tester.create }));

    expect(await screen.findByText(URL)).toBeInTheDocument();
    const command = screen.getByText((_, node) => node?.tagName === 'CODE' && /^curl /.test(node.textContent ?? ''));
    expect(command.textContent).toContain(URL);
    expect(localStorage.getItem(STORAGE_KEY)).toBe(SLUG);
    expect(await screen.findByText(en.tester.waiting)).toBeInTheDocument();
  });

  it('comes back to the remembered URL and shows what it received, newest first', async () => {
    localStorage.setItem(STORAGE_KEY, SLUG);
    vi.mocked(publicBinApi.get).mockResolvedValue(WITH_REQUESTS);
    renderTester();

    const list = await screen.findByRole('list', { name: en.tester.requests });
    const items = within(list).getAllByRole('button');
    expect(items.map((i) => i.textContent)).toEqual([
      expect.stringContaining('PUT'),
      expect.stringContaining('POST'),
    ]);

    await userEvent.click(items[1]);
    expect(await screen.findByText('Stripe-Signature')).toBeInTheDocument();
    expect(screen.getByText(/"invoice\.paid"/)).toBeInTheDocument();
    expect(screen.getByText('attempt=1')).toBeInTheDocument();
    expect(publicBinApi.create).not.toHaveBeenCalled();
  });

  it('starts over when the remembered URL has expired', async () => {
    localStorage.setItem(STORAGE_KEY, SLUG);
    vi.mocked(publicBinApi.get).mockRejectedValue({ response: { status: 404 } });
    renderTester();

    expect(await screen.findByRole('button', { name: en.tester.create })).toBeInTheDocument();
    await waitFor(() => expect(localStorage.getItem(STORAGE_KEY)).toBeNull());
  });

  it('says what it keeps and for how long before anyone relies on it', () => {
    renderTester();
    expect(screen.getByRole('heading', { name: en.tester.limits.title })).toBeInTheDocument();
    expect(screen.getByText(en.tester.limits.lifetime)).toBeInTheDocument();
    expect(screen.getByText(en.tester.limits.kept)).toBeInTheDocument();
  });

  it('explains a refusal in words: three live URLs per address', async () => {
    vi.mocked(publicBinApi.create).mockRejectedValue({
      response: { status: 429, data: { error: 'too_many_active_urls' } },
    });
    renderTester();
    await userEvent.click(screen.getByRole('button', { name: en.tester.create }));
    expect(await screen.findByRole('alert')).toHaveTextContent(en.tester.errors.tooManyActive);
  });

  it('on a server that has not turned it on, says so and offers nothing', () => {
    window.__RAILHOOK__ = {};
    renderTester();
    expect(screen.getByText(en.tester.disabled)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: en.tester.create })).toBeNull();
  });

  it('points at what an account adds: keep, retry and forward', async () => {
    renderTester();
    const cta = screen.getByRole('link', { name: en.tester.cta.button });
    expect(cta).toHaveAttribute('href', '/register');
  });
});
