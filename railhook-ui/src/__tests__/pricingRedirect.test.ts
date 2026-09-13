import { describe, it, expect } from 'vitest';
import { router } from '../router';

/**
 * /pricing was a page of its own and the link people pasted into chats. There are no paid plans
 * to price any more, but the links are still out there: they land on the section that says how
 * to run Railhook, free in the cloud or on your own servers, rather than on a 404.
 */
describe('/pricing', () => {
  it('redirects to the run-it-your-way section of the landing page', async () => {
    await router.navigate('/pricing');
    expect(router.state.location.pathname).toBe('/');
    expect(router.state.location.hash).toBe('#run');
  });
});
