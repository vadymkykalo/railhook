import { describe, it, expect, afterEach } from 'vitest';
import { act, render, waitFor } from '@testing-library/react';
import { toast } from 'sonner';
import ThemedToaster from '../ThemedToaster';
import { applyTheme } from '../../lib/theme';

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
