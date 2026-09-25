import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import '../../../i18n';
import en from '../../../i18n/locales/en.json';
import { createTestQueryClient } from '../../../test/renderPage';

vi.mock('../../../api/contact.api', () => ({ contactApi: { send: vi.fn() } }));

import ContactForm from '../ContactForm';
import { contactApi } from '../../../api/contact.api';

function renderForm() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <MemoryRouter initialEntries={['/pricing']}>
        <ContactForm />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  window.__RAILHOOK__ = { contactDomain: 'railhook.io' };
});

afterEach(() => {
  delete window.__RAILHOOK__;
});

describe('ContactForm', () => {
  it('sends the message with the page it came from, and says where the reply goes', async () => {
    vi.mocked(contactApi.send).mockResolvedValue({ status: 'sent' });
    renderForm();

    await userEvent.click(screen.getByRole('radio', { name: en.site.contact.topics.sales }));
    await userEvent.type(screen.getByLabelText(en.site.contact.emailLabel), 'ada@example.com');
    await userEvent.type(screen.getByLabelText(en.site.contact.messageLabel), 'Two million events a month');
    await userEvent.click(screen.getByRole('button', { name: en.site.contact.send }));

    await waitFor(() => expect(contactApi.send).toHaveBeenCalledWith({
      email: 'ada@example.com', name: undefined, topic: 'sales',
      message: 'Two million events a month', page: '/pricing', captchaToken: undefined,
    }));
    expect(await screen.findByText(en.site.contact.sentTitle)).toBeInTheDocument();
    expect(screen.getByText(/ada@example\.com/)).toBeInTheDocument();
  });

  it('explains a refusal instead of failing silently', async () => {
    vi.mocked(contactApi.send).mockRejectedValue({ response: { status: 429, data: { error: 'rate_limit_exceeded' } } });
    renderForm();

    await userEvent.type(screen.getByLabelText(en.site.contact.emailLabel), 'ada@example.com');
    await userEvent.type(screen.getByLabelText(en.site.contact.messageLabel), 'Hello');
    await userEvent.click(screen.getByRole('button', { name: en.site.contact.send }));

    expect(await screen.findByRole('alert')).toHaveTextContent(en.site.contact.errors.tooMany);
  });
});
