import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import i18n from '../../i18n';
import PageGuide, { PAGE_GUIDES_KEY } from '../PageGuide';
import PageHeader from '../PageHeader';

describe('PageGuide', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('lists the page’s steps in order, open by default', () => {
    render(<PageGuide id="consumers" docsLink="outgoing/customer-portal" />);
    const toggle = screen.getByRole('button', { name: i18n.t('guides.title') });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    const steps = screen.getAllByRole('listitem').map((li) => li.textContent);
    expect(steps.length).toBeGreaterThanOrEqual(2);
    expect(steps.length).toBeLessThanOrEqual(4);
    expect(steps[0]).toContain(i18n.t('guides.consumers.step1').slice(0, 20));
    expect(screen.getByRole('link')).toHaveAttribute('href', '/docs/outgoing/customer-portal/');
  });

  it('stays collapsed on the next visit once a reader collapses it, per page', async () => {
    const user = userEvent.setup();
    const { unmount } = render(<PageGuide id="consumers" />);
    await user.click(screen.getByRole('button', { name: i18n.t('guides.title') }));
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    unmount();

    render(
      <>
        <PageGuide id="consumers" />
        <PageGuide id="endpoints" />
      </>,
    );
    const [consumers, endpoints] = screen.getAllByRole('button', { name: i18n.t('guides.title') });
    expect(consumers).toHaveAttribute('aria-expanded', 'false');
    expect(endpoints).toHaveAttribute('aria-expanded', 'true');
    expect(JSON.parse(localStorage.getItem(PAGE_GUIDES_KEY)!)).toEqual({ consumers: 'collapsed' });
  });

  it('still opens and closes when the browser refuses storage', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const user = userEvent.setup();
    render(<PageGuide id="events" />);
    const toggle = screen.getByRole('button', { name: i18n.t('guides.title') });
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('is what a page header renders under itself when given one', () => {
    render(<PageHeader title="Consumers" guide={{ id: 'consumers' }} />);
    expect(screen.getByRole('heading', { name: 'Consumers' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: i18n.t('guides.title') })).toBeInTheDocument();
  });
});
