import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Button } from '../ui/button';

/** Slot needs exactly one child; a stray `false` child white-screened every <Button asChild>. */
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
