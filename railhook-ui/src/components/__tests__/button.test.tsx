import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Button } from '../ui/button';

/**
 * `asChild` renders the button's styling onto whatever element is passed —
 * a Link, an anchor — via Radix's Slot, which requires exactly one element
 * child. Button used to render `{isLoading && <Loader2/>}{children}`
 * unconditionally, so Slot always received two children: `false` and the
 * element. Every page using `<Button asChild>` white-screened with
 * "Slot failed to slot onto its children" — Incidents, Alerts, Billing,
 * Not Found and Access Denied.
 */
describe('Button with asChild', () => {
  it('renders the child element instead of a button', () => {
    render(
      <Button asChild>
        <a href="/somewhere">Go</a>
      </Button>,
    );

    const link = screen.getByRole('link', { name: 'Go' });
    expect(link).toHaveAttribute('href', '/somewhere');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('carries the button styling onto the child', () => {
    render(
      <Button asChild variant="outline" size="sm">
        <a href="/somewhere">Go</a>
      </Button>,
    );

    expect(screen.getByRole('link', { name: 'Go' }).className).toContain('inline-flex');
  });

  it('accepts a child that has several children of its own', () => {
    // The shape that crashed in production: an icon and a label inside the link.
    render(
      <Button asChild>
        <a href="/somewhere">
          <svg aria-hidden />
          Investigate
        </a>
      </Button>,
    );

    expect(screen.getByRole('link', { name: 'Investigate' })).toBeInTheDocument();
  });
});

describe('Button without asChild', () => {
  it('still shows a spinner while loading, and disables itself', () => {
    const { container } = render(<Button isLoading>Saving</Button>);

    expect(screen.getByRole('button', { name: /Saving/ })).toBeDisabled();
    expect(container.querySelector('.animate-spin')).not.toBeNull();
  });

  it('shows no spinner when it is not loading', () => {
    const { container } = render(<Button>Save</Button>);

    expect(container.querySelector('.animate-spin')).toBeNull();
  });
});
