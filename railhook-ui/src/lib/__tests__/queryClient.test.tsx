import type { ReactNode } from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { QueryClientProvider, useMutation } from '@tanstack/react-query';
import '../../i18n';

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), success: vi.fn(), warning: vi.fn(), info: vi.fn() },
}));

import { toast } from 'sonner';
import { createQueryClient } from '../queryClient';
import { showApiError } from '../toast';

function failure() {
  return { response: { status: 500, data: { message: 'boom' } } };
}

function wrapperFor() {
  const client = createQueryClient();
  client.setDefaultOptions({ ...client.getDefaultOptions(), mutations: { ...client.getDefaultOptions().mutations, retry: false } });
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/** Lets the mutation settle and any deferred fallback run. */
async function settle() {
  await act(async () => { await new Promise((r) => setTimeout(r, 20)); });
}

describe('the app QueryClient mutation error net', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows exactly one toast when a mutateAsync caller catches and reports the error itself', async () => {
    const { result } = renderHook(
      () => useMutation({ mutationFn: () => Promise.reject(failure()) }),
      { wrapper: wrapperFor() },
    );

    await act(async () => {
      try {
        await result.current.mutateAsync();
      } catch (err) {
        showApiError(err, 'endpoints.toast.createFailed');
      }
    });
    await settle();

    expect(toast.error).toHaveBeenCalledTimes(1);
  });

  it('shows exactly one toast when mutate() is given its own onError', async () => {
    const { result } = renderHook(
      () => useMutation({ mutationFn: () => Promise.reject(failure()) }),
      { wrapper: wrapperFor() },
    );

    act(() => {
      result.current.mutate(undefined, { onError: (err) => showApiError(err, 'members.toast.removeFailed') });
    });
    await settle();

    expect(toast.error).toHaveBeenCalledTimes(1);
  });

  it('stays silent when the mutation declares it handles its errors inline', async () => {
    const { result } = renderHook(
      () => useMutation({ mutationFn: () => Promise.reject(failure()), meta: { handlesError: true } }),
      { wrapper: wrapperFor() },
    );

    act(() => { result.current.mutate(); });
    await settle();

    expect(toast.error).not.toHaveBeenCalled();
  });

  it('still reports a failure nobody handled, once', async () => {
    const { result } = renderHook(
      () => useMutation({ mutationFn: () => Promise.reject(failure()) }),
      { wrapper: wrapperFor() },
    );

    act(() => { result.current.mutate(); });
    await settle();

    expect(toast.error).toHaveBeenCalledTimes(1);
    expect(toast.error).toHaveBeenCalledWith('boom', expect.anything());
  });
});
