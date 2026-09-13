import { describe, it, expect, afterEach } from 'vitest';
import { act, render, waitFor } from '@testing-library/react';
import { toast } from 'sonner';
import ThemedToaster from '../ThemedToaster';
import { applyTheme } from '../../lib/theme';

/**
 * Sonner draws its own light palette unless told otherwise, so on the dark theme a "Welcome
 * back" toast arrived as a pale green slab. The toaster has to follow the theme that is applied
 * to the document, including when the reader flips it while a toast is still on screen.
 */
function toasterTheme() {
  return document.querySelector('[data-sonner-toaster]')?.getAttribute('data-sonner-theme');
}

afterEach(() => {
  document.documentElement.classList.remove('light', 'dark');
});

describe('ThemedToaster', () => {
  it('draws toasts in the theme applied to the document and follows a switch', async () => {
    applyTheme('light');
    render(<ThemedToaster />);
    act(() => {
      toast('Saved');
    });
    await waitFor(() => expect(toasterTheme()).toBe('light'));

    act(() => {
      applyTheme('dark');
    });
    await waitFor(() => expect(toasterTheme()).toBe('dark'));
  });
});
