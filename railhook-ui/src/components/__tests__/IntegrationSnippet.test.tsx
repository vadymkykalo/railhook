import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import '../../i18n';
import IntegrationSnippet, { SNIPPET_LANGUAGE_KEY } from '../IntegrationSnippet';

const SAMPLES = {
  php: '<?php echo "php";',
  curl: 'curl -X POST https://api.example.test/api/v1/events',
  node: "import { Railhook } from '@railhook/node';",
  python: 'from railhook import Railhook',
};

function activeCode(): string {
  return screen.getByRole('tabpanel').textContent ?? '';
}

describe('IntegrationSnippet', () => {
  let writeText: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('offers the languages it was given, always in the same order', () => {
    render(<IntegrationSnippet title="Send an event" samples={SAMPLES} />);
    const tabs = screen.getAllByRole('tab').map((tab) => tab.textContent);
    expect(tabs).toEqual(['cURL', 'Node.js', 'Python', 'PHP']);
  });

  it('shows only the languages that have a sample', () => {
    render(<IntegrationSnippet title="Verify" samples={{ node: SAMPLES.node, python: SAMPLES.python }} />);
    expect(screen.getAllByRole('tab').map((tab) => tab.textContent)).toEqual(['Node.js', 'Python']);
    expect(activeCode()).toContain('@railhook/node');
  });

  it('switches the code with the tab, and remembers the choice for the next snippet', async () => {
    const user = userEvent.setup();
    const { unmount } = render(<IntegrationSnippet title="Send an event" samples={SAMPLES} />);
    expect(activeCode()).toContain('curl -X POST');

    await user.click(screen.getByRole('tab', { name: 'Python' }));
    expect(screen.getByRole('tab', { name: 'Python' })).toHaveAttribute('aria-selected', 'true');
    expect(activeCode()).toContain('from railhook import Railhook');
    expect(localStorage.getItem(SNIPPET_LANGUAGE_KEY)).toBe('python');

    unmount();
    render(<IntegrationSnippet title="Another" samples={SAMPLES} />);
    expect(screen.getByRole('tab', { name: 'Python' })).toHaveAttribute('aria-selected', 'true');
  });

  it('keeps two snippets on one page on the same language', async () => {
    const user = userEvent.setup();
    render(
      <>
        <IntegrationSnippet title="First" samples={SAMPLES} />
        <IntegrationSnippet title="Second" samples={SAMPLES} />
      </>,
    );
    const [first] = screen.getAllByRole('tablist');
    await user.click(within(first).getByRole('tab', { name: 'PHP' }));
    for (const panel of screen.getAllByRole('tabpanel')) {
      expect(panel.textContent).toContain('<?php');
    }
  });

  it('falls back to the first sample when the remembered language is not offered here', () => {
    localStorage.setItem(SNIPPET_LANGUAGE_KEY, 'curl');
    render(<IntegrationSnippet title="Verify" samples={{ node: SAMPLES.node, php: SAMPLES.php }} />);
    expect(screen.getByRole('tab', { name: 'Node.js' })).toHaveAttribute('aria-selected', 'true');
  });

  it('still renders when the browser refuses storage', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    const user = userEvent.setup();
    render(<IntegrationSnippet title="Send an event" samples={SAMPLES} />);
    await user.click(screen.getByRole('tab', { name: 'Node.js' }));
    expect(activeCode()).toContain('@railhook/node');
  });

  it('copies exactly the code on the active tab', async () => {
    const user = userEvent.setup();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    render(<IntegrationSnippet title="Send an event" samples={SAMPLES} />);
    await user.click(screen.getByRole('tab', { name: 'Node.js' }));
    await user.click(screen.getByRole('button', { name: /copy/i }));
    expect(writeText).toHaveBeenCalledWith(SAMPLES.node);
  });

  it('links to the docs page in the reader’s language', () => {
    render(<IntegrationSnippet title="Send an event" samples={SAMPLES} docsLink="outgoing/customer-portal" />);
    expect(screen.getByRole('link')).toHaveAttribute('href', '/docs/outgoing/customer-portal/');
  });
});
