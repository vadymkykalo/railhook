import { useContext, useEffect } from 'react';
import { AuthContext } from './auth.store';

/**
 * Ends a live-demo session on arrival. For the sign-in and sign-up pages: whoever opens them from
 * the demo is about to become someone, and the demo's token must not ride along into it.
 */
export function useLeaveDemo(): void {
  const context = useContext(AuthContext);
  const inDemo = context?.user?.demo === true;
  useEffect(() => {
    if (inDemo) context?.logout();
  }, [inDemo]); // eslint-disable-line react-hooks/exhaustive-deps
}
