import { describe, it, expect, beforeAll, afterEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import ProductSection from '../landing/ProductSection';
import { renderPage } from '../../test/renderPage';
import en from '../../i18n/locales/en.json';

/**
 * "See it in action": the real product in six screenshots behind tabs. What these hold is what a
 * visitor relies on — one screen at a time with its own caption, arrow keys that move between the
 * tabs as a tablist should, and one image that follows the theme and reserves its space before it loads.
 */
const copy = en.landing.product;
const ORDER = ['deliveries', 'attempts', 'failed', 'incoming', 'portal', 'analytics'] as const;

function renderSection() {
  return renderPage(<ProductSection />, { path: '/', initialEntry: '/' });
}

function tab(id: (typeof ORDER)[number]) {
  return screen.getByRole('tab', { name: copy[id].tab });
}

beforeAll(() => {
  // jsdom has no layout, so no scrollIntoView.
  Element.prototype.scrollIntoView = () => {};
});

afterEach(() => {
  localStorage.removeItem('theme');
  document.documentElement.classList.remove('dark', 'light');
});

describe('ProductSection', () => {
  it('offers the six screens as one tablist, the deliveries list first and selected', () => {
    renderSection();
    const tablist = screen.getByRole('tablist', { name: copy.tabsLabel });
    const tabs = within(tablist).getAllByRole('tab');
    expect(tabs.map((t) => t.textContent)).toEqual(ORDER.map((id) => copy[id].tab));
    expect(tab('deliveries')).toHaveAttribute('aria-selected', 'true');
    expect(tabs.filter((t) => t.getAttribute('tabindex') === '0')).toEqual([tab('deliveries')]);
  });

  it('shows the selected screen’s caption and one screenshot, with its size reserved before it loads', () => {
    renderSection();
    const panel = screen.getByRole('tabpanel');
    expect(panel).toHaveAttribute('aria-labelledby', tab('deliveries').id);
    expect(within(panel).getByText(copy.deliveries.caption)).toBeInTheDocument();

    const img = within(panel).getByRole('img', { name: copy.deliveries.alt });
    expect(img).toHaveAttribute('src', '/screens/deliveries-light.webp');
    expect(img).toHaveAttribute('loading', 'lazy');
    expect(img).toHaveAttribute('width', '1440');
    expect(img).toHaveAttribute('height', '900');
  });

  it('offers the dark screenshot by the system preference until the reader picks a theme', () => {
    renderSection();
    const source = screen.getByRole('tabpanel').querySelector('picture source');
    expect(source).toHaveAttribute('srcset', '/screens/deliveries-dark.webp');
    expect(source).toHaveAttribute('media', '(prefers-color-scheme: dark)');
  });

  it('follows the theme the reader picked, and a switch made while the page is open', async () => {
    localStorage.setItem('theme', 'dark');
    document.documentElement.classList.add('dark');
    renderSection();
    const source = () => screen.getByRole('tabpanel').querySelector('picture source');
    expect(source()).toHaveAttribute('media', 'all');

    localStorage.setItem('theme', 'light');
    document.documentElement.classList.replace('dark', 'light');
    await waitFor(() => expect(source()).toHaveAttribute('media', 'not all'));
  });

  it('switches screen on click', () => {
    renderSection();
    fireEvent.click(tab('portal'));
    expect(tab('portal')).toHaveAttribute('aria-selected', 'true');
    expect(tab('deliveries')).toHaveAttribute('aria-selected', 'false');
    const panel = screen.getByRole('tabpanel');
    expect(within(panel).getByText(copy.portal.caption)).toBeInTheDocument();
    expect(within(panel).getByRole('img', { name: copy.portal.alt })).toHaveAttribute('src', '/screens/portal-light.webp');
    expect(within(panel).queryByText(copy.deliveries.caption)).toBeNull();
  });

  it('moves between screens with the arrow keys, wrapping at both ends, and Home and End', () => {
    renderSection();
    fireEvent.keyDown(tab('deliveries'), { key: 'ArrowRight' });
    expect(tab('attempts')).toHaveAttribute('aria-selected', 'true');
    expect(tab('attempts')).toHaveFocus();

    fireEvent.keyDown(tab('attempts'), { key: 'ArrowLeft' });
    fireEvent.keyDown(tab('deliveries'), { key: 'ArrowLeft' });
    expect(tab('analytics')).toHaveAttribute('aria-selected', 'true');
    expect(tab('analytics')).toHaveFocus();

    fireEvent.keyDown(tab('analytics'), { key: 'ArrowRight' });
    expect(tab('deliveries')).toHaveAttribute('aria-selected', 'true');

    fireEvent.keyDown(tab('deliveries'), { key: 'End' });
    expect(tab('analytics')).toHaveAttribute('aria-selected', 'true');
    fireEvent.keyDown(tab('analytics'), { key: 'Home' });
    expect(tab('deliveries')).toHaveAttribute('aria-selected', 'true');
    expect(tab('deliveries')).toHaveFocus();
  });

  it('ignores keys that are not for moving between tabs', () => {
    renderSection();
    fireEvent.keyDown(tab('deliveries'), { key: 'ArrowDown' });
    fireEvent.keyDown(tab('deliveries'), { key: 'a' });
    expect(tab('deliveries')).toHaveAttribute('aria-selected', 'true');
  });
});
