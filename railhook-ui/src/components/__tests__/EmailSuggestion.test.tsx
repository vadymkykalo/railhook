import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import i18n from '../../i18n';
import EmailSuggestion from '../EmailSuggestion';

describe('EmailSuggestion', () => {
  it('offers the likely address and fills it in with one click', async () => {
    const onAccept = vi.fn();
    render(<EmailSuggestion email="wheelet1228@gmail.con" onAccept={onAccept} />);

    expect(screen.getByText(/did you mean/i)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'wheelet1228@gmail.com' }));

    expect(onAccept).toHaveBeenCalledWith('wheelet1228@gmail.com');
  });

  it('says an impossible ending cannot receive mail, not only that it looks odd', () => {
    render(<EmailSuggestion email="a@acme.con" onAccept={() => {}} />);
    expect(screen.getByRole('alert')).toHaveTextContent(/cannot receive mail/i);
  });

  it('is a quiet hint for a near miss of a popular domain', () => {
    render(<EmailSuggestion email="a@gmial.com" onAccept={() => {}} />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('a@gmail.com');
  });

  it('renders nothing for an address that looks right', () => {
    const { container } = render(<EmailSuggestion email="a@gmail.com" onAccept={() => {}} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('speaks Ukrainian', async () => {
    await i18n.changeLanguage('uk');
    try {
      render(<EmailSuggestion email="a@gmail.con" onAccept={() => {}} />);
      expect(screen.getByRole('alert')).toHaveTextContent(/мали на увазі/i);
    } finally {
      await i18n.changeLanguage('en');
    }
  });
});
