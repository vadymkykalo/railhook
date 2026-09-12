import { beforeEach, describe, expect, it, vi } from 'vitest';

import { clientErrorsApi } from '../../api/clientErrors.api';
import { http } from '../../api/http';
import { reportClientError, resetClientErrorReporterForTests } from '../reportClientError';

/**
 * This runs on a page that has already failed, which decides most of its design: it must not
 * throw, it must not retry, and it must not be able to make things worse by talking more than
 * the person looking at the screen can act on.
 */
describe('reportClientError', () => {
  beforeEach(() => {
    resetClientErrorReporterForTests();
    http.setToken('a-token');
    vi.restoreAllMocks();
  });

  it('posts the failure to this installation, not to anyone else', async () => {
    const post = vi.spyOn(clientErrorsApi, 'report').mockResolvedValue(undefined);

    await reportClientError(new Error('boom'), { componentStack: '  at DeliveriesPage' });

    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][0]).toMatchObject({
      message: 'boom',
      componentStack: '  at DeliveriesPage',
    });
  });

  it('says which page broke', async () => {
    const post = vi.spyOn(clientErrorsApi, 'report').mockResolvedValue(undefined);

    await reportClientError(new Error('boom'));

    expect(post.mock.calls[0][0].url).toBe(window.location.href);
  });

  it('never throws when the report itself fails', async () => {
    vi.spyOn(clientErrorsApi, 'report').mockRejectedValue(new Error('backend is down too'));

    await expect(reportClientError(new Error('boom'))).resolves.toBeUndefined();
  });

  it('stays quiet when nobody is signed in, because the endpoint would only answer 401', async () => {
    http.setToken(null);
    const post = vi.spyOn(clientErrorsApi, 'report').mockResolvedValue(undefined);

    await reportClientError(new Error('boom'));

    expect(post).not.toHaveBeenCalled();
  });

  it('reports the same failure once, however many times it recurs', async () => {
    const post = vi.spyOn(clientErrorsApi, 'report').mockResolvedValue(undefined);

    await reportClientError(new Error('the same boom'));
    await reportClientError(new Error('the same boom'));
    await reportClientError(new Error('the same boom'));

    expect(post).toHaveBeenCalledTimes(1);
  });

  it('still reports a different failure', async () => {
    const post = vi.spyOn(clientErrorsApi, 'report').mockResolvedValue(undefined);

    await reportClientError(new Error('one thing'));
    await reportClientError(new Error('another thing'));

    expect(post).toHaveBeenCalledTimes(2);
  });

  it('stops after enough distinct failures, rather than following the page down', async () => {
    const post = vi.spyOn(clientErrorsApi, 'report').mockResolvedValue(undefined);

    for (let i = 0; i < 50; i++) {
      await reportClientError(new Error(`failure ${i}`));
    }

    expect(post.mock.calls.length).toBeLessThanOrEqual(10);
  });

  it('handles a thrown value that is not an Error at all', async () => {
    const post = vi.spyOn(clientErrorsApi, 'report').mockResolvedValue(undefined);

    await reportClientError('a bare string' as unknown as Error);

    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][0].message).toContain('a bare string');
  });
});
