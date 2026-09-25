import { createHmac } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'jest-axe';
import '../../i18n';
import i18n from '../../i18n';
import en from '../../i18n/locales/en.json';
import uk from '../../i18n/locales/uk.json';
import { renderPage } from '../../test/renderPage';
import SignatureVerifierPage from '../SignatureVerifierPage';

function renderVerifier() {
  return renderPage(<SignatureVerifierPage />, {
    path: '/tools/webhook-signature',
    initialEntry: '/tools/webhook-signature',
    auth: { user: null, token: null, isAuthenticated: false },
  });
}

const SECRET = "It's a Secret to Everybody";
const BODY = 'Hello, World!';
const GITHUB_SIGNATURE = `sha256=${createHmac('sha256', SECRET).update(BODY).digest('hex')}`;

function paste(label: string | RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

let fetchSpy: ReturnType<typeof vi.spyOn>;
let xhrSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, 'fetch');
  xhrSpy = vi.spyOn(XMLHttpRequest.prototype, 'open');
});

afterEach(async () => {
  fetchSpy.mockRestore();
  xhrSpy.mockRestore();
  await i18n.changeLanguage('en');
});

describe('SignatureVerifierPage', () => {
  it('names the page for search and describes itself as a web application', () => {
    renderVerifier();
    expect(document.title).toBe(en.meta.signatureVerifier.title);
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toMatch(/\/tools\/webhook-signature$/);
    const node = document.head.querySelector('script[type="application/ld+json"][data-page="signature-verifier"]');
    const data = JSON.parse(node?.textContent ?? '{}');
    expect(data['@type']).toBe('WebApplication');
    expect(data.offers).toMatchObject({ price: '0' });
  });

  it('says up front that the secret never leaves the browser', () => {
    renderVerifier();
    expect(screen.getByText(en.webhookSignature.private)).toBeInTheDocument();
  });

  it('confirms a GitHub signature that matches', async () => {
    renderVerifier();
    await userEvent.click(screen.getByRole('radio', { name: en.webhookSignature.providers.github }));
    paste(en.webhookSignature.payload, BODY);
    paste(en.webhookSignature.secret, SECRET);
    paste('X-Hub-Signature-256', GITHUB_SIGNATURE);
    expect(await screen.findByText(en.webhookSignature.result.valid)).toBeInTheDocument();
  });

  it('shows the signature it expected when the body does not match', async () => {
    renderVerifier();
    await userEvent.click(screen.getByRole('radio', { name: en.webhookSignature.providers.github }));
    paste(en.webhookSignature.payload, `${BODY}!`);
    paste(en.webhookSignature.secret, SECRET);
    paste('X-Hub-Signature-256', GITHUB_SIGNATURE);
    const result = await screen.findByRole('status');
    expect(await within(result).findByText(en.webhookSignature.result.invalid)).toBeInTheDocument();
    const expected = `sha256=${createHmac('sha256', SECRET).update(`${BODY}!`).digest('hex')}`;
    expect(within(result).getByText(expected)).toBeInTheDocument();
  });

  it('asks for the headers of the provider chosen', async () => {
    renderVerifier();
    expect(screen.getByLabelText('webhook-signature')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: en.webhookSignature.providers.slack }));
    expect(screen.getByLabelText('X-Slack-Signature')).toBeInTheDocument();
    expect(screen.getByLabelText('X-Slack-Request-Timestamp')).toBeInTheDocument();
    expect(screen.queryByLabelText('webhook-signature')).toBeNull();
  });

  it('fills in an example that verifies', async () => {
    renderVerifier();
    await userEvent.click(screen.getByRole('radio', { name: en.webhookSignature.providers.stripe }));
    await userEvent.click(screen.getByRole('button', { name: en.webhookSignature.example }));
    expect(await screen.findByText(en.webhookSignature.result.valid)).toBeInTheDocument();
  });

  it('warns when a matching signature was made outside the provider’s time window', async () => {
    renderVerifier();
    await userEvent.click(screen.getByRole('radio', { name: en.webhookSignature.providers.stripe }));
    const t = Math.floor(Date.now() / 1000) - 3600;
    paste(en.webhookSignature.payload, BODY);
    paste(en.webhookSignature.secret, 'whsec_test');
    paste('Stripe-Signature', `t=${t},v1=${createHmac('sha256', 'whsec_test').update(`${t}.${BODY}`).digest('hex')}`);
    expect(await screen.findByText(en.webhookSignature.result.valid)).toBeInTheDocument();
    expect(screen.getByRole('status').textContent).toMatch(/outside the 5-minute window/);
  });

  it('sends nothing anywhere while it checks', async () => {
    renderVerifier();
    await userEvent.click(screen.getByRole('radio', { name: en.webhookSignature.providers.github }));
    paste(en.webhookSignature.payload, BODY);
    paste(en.webhookSignature.secret, SECRET);
    paste('X-Hub-Signature-256', GITHUB_SIGNATURE);
    await screen.findByText(en.webhookSignature.result.valid);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(xhrSpy).not.toHaveBeenCalled();
  });

  it('points to Railhook doing this on every incoming webhook', () => {
    renderVerifier();
    const cta = screen.getByRole('heading', { name: en.webhookSignature.cta.title }).closest('section') as HTMLElement;
    expect(within(cta).getByRole('link', { name: en.webhookSignature.cta.button })).toHaveAttribute('href', '/register');
    expect(within(cta).getByRole('link', { name: en.webhookSignature.cta.docs })).toHaveAttribute('href', '/docs/incoming/verification/');
  });

  it('switches language', async () => {
    await i18n.changeLanguage('uk');
    renderVerifier();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(uk.webhookSignature.title);
  });

  it('has no detectable accessibility violations', async () => {
    const { container } = renderVerifier();
    expect(await axe(container)).toHaveNoViolations();
  });
});
