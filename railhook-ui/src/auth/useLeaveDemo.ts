import { useContext, useEffect } from 'react';
import { AuthContext } from './auth.store';

/** Whoever signs in or up from the demo must not carry its token along. */
export function useLeaveDemo(): void {
  const context = useContext(AuthContext);
  const inDemo = context?.user?.demo === true;
  useEffect(() => {
    if (inDemo) context?.logout();
  }, [inDemo]); // eslint-disable-line react-hooks/exhaustive-deps
}
