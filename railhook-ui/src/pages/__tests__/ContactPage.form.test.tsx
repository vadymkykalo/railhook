import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage } from '../../test/renderPage';

vi.mock('../../api/contact.api', () => ({ contactApi: { send: vi.fn() } }));

import ContactPage from '../ContactPage';
import { contactApi } from '../../api/contact.api';

function renderContact() {
  return renderPage(<ContactPage />, {
    path: '/contact', initialEntry: '/contact', auth: { user: null, token: null, isAuthenticated: false },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  window.__RAILHOOK__ = { contactDomain: 'railhook.io' };
});

afterEach(() => {
  delete window.__RAILHOOK__;
});

describe('ContactPage form', () => {
  it('sends the message with the page it came from, and says where the reply goes', async () => {
    vi.mocked(contactApi.send).mockResolvedValue({ status: 'sent' });
    renderContact();

    await userEvent.click(screen.getByRole('radio', { name: en.site.contact.topics.sales }));
    await userEvent.type(screen.getByLabelText(en.site.contact.emailLabel), 'ada@example.com');
    await userEvent.type(screen.getByLabelText(en.site.contact.messageLabel), 'Two million events a month');
    await userEvent.click(screen.getByRole('button', { name: en.site.contact.send }));

    await waitFor(() => expect(contactApi.send).toHaveBeenCalledWith({
      email: 'ada@example.com', name: undefined, topic: 'sales',
      message: 'Two million events a month', page: '/contact', captchaToken: undefined,
    }));
    expect(await screen.findByText(en.site.contact.sentTitle)).toBeInTheDocument();
    expect(screen.getByText(/ada@example\.com/)).toBeInTheDocument();
  });

  it('explains a refusal instead of failing silently', async () => {
    vi.mocked(contactApi.send).mockRejectedValue({ response: { status: 429, data: { error: 'rate_limit_exceeded' } } });
    renderContact();

    await userEvent.type(screen.getByLabelText(en.site.contact.emailLabel), 'ada@example.com');
    await userEvent.type(screen.getByLabelText(en.site.contact.messageLabel), 'Hello');
    await userEvent.click(screen.getByRole('button', { name: en.site.contact.send }));

    expect(await screen.findByRole('alert')).toHaveTextContent(en.site.contact.errors.tooMany);
  });

  it('has no form where there is no support address', () => {
    window.__RAILHOOK__ = {};
    renderContact();
    expect(screen.queryByRole('button', { name: en.site.contact.send })).not.toBeInTheDocument();
  });
});
