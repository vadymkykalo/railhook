import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Check, ChevronsUpDown, Loader2 } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/auth.store';
import { useOrganizations } from '../api/queries';
import { authApi } from '../api/auth.api';
import { http } from '../api/http';
import { showApiError } from '../lib/toast';
import { cn } from '../lib/utils';

/**
 * Which organization you are looking at, for the people who are in more than one.
 *
 * <p>Accepting a second invite used to be silent: the backend listed both organizations and
 * login always minted a token for the oldest membership, so the second one was unreachable
 * from the dashboard however many times you accepted.
 *
 * Rendered only when there is a choice to make. A switcher over a list of one is a control
 * that answers a question nobody asked, so with a single organization the sidebar keeps the
 * plain name it always had.
 */
export default function OrganizationSwitcher({ collapsed }: { collapsed?: boolean }) {
  const { t } = useTranslation();
  const { user, login } = useAuth();
  const { data: organizations = [] } = useOrganizations();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [switchingTo, setSwitchingTo] = useState<string | null>(null);
  const ref = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const currentId = user?.organization?.id;
  const currentName = user?.organization?.name ?? '';

  if (organizations.length < 2) {
    return currentName ? (
      <p className="truncate text-[11px] leading-tight text-muted-foreground">{currentName}</p>
    ) : null;
  }

  const handleSwitch = async (organizationId: string) => {
    if (organizationId === currentId || switchingTo) return;
    setSwitchingTo(organizationId);
    try {
      const { accessToken } = await authApi.switchOrganization(organizationId);
      // The token has to be in place before /auth/me is asked anything, or the answer comes
      // back describing the organization we are trying to leave.
      http.setToken(accessToken);
      const me = await authApi.getCurrentUser();
      login(accessToken, me);
      // Everything cached is scoped to the organization we just left — projects, endpoints,
      // deliveries, every count on the dashboard. Dropping the lot is the only honest option;
      // invalidating selectively would leave whichever list nobody thought of showing the old
      // organization's rows under the new organization's name.
      queryClient.clear();
      setOpen(false);
      navigate('/admin/dashboard');
    } catch (error) {
      showApiError(error, 'org.switchFailed');
    } finally {
      setSwitchingTo(null);
    }
  };

  const menu = open && (
    <div
      className={cn(
        'absolute bottom-full z-50 mb-1 overflow-hidden rounded-lg border border-rail bg-popover shadow-elevated animate-scale-in',
        collapsed ? 'left-0 w-56' : 'left-0 right-0'
      )}
    >
      <p className="mono-label border-b border-rail px-2.5 py-2 text-muted-foreground">
        {t('org.switcherLabel')}
      </p>
      <ul role="listbox" aria-label={t('org.switcherLabel')} className="max-h-[280px] overflow-y-auto p-1">
        {organizations.map((organization) => {
          const current = organization.id === currentId;
          return (
            <li key={organization.id} role="option" aria-selected={current}>
              <button
                onClick={() => handleSwitch(organization.id)}
                disabled={!!switchingTo}
                className={cn(
                  'flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-[13px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60',
                  current
                    ? 'bg-accent/60 font-medium text-foreground'
                    : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
                )}
              >
                <span
                  aria-hidden
                  className={cn(
                    'flex h-5 w-5 flex-shrink-0 items-center justify-center rounded font-mono text-[10px]',
                    current ? 'bg-primary text-primary-foreground' : 'bg-secondary text-muted-foreground'
                  )}
                >
                  {organization.name.charAt(0).toUpperCase()}
                </span>
                <span className="min-w-0 flex-1 truncate">{organization.name}</span>
                {switchingTo === organization.id && (
                  <Loader2 className="h-3.5 w-3.5 flex-shrink-0 animate-spin text-primary" aria-hidden />
                )}
                {current && !switchingTo && (
                  <Check className="h-3.5 w-3.5 flex-shrink-0 text-primary" aria-hidden />
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );

  return (
    <div ref={ref} className="relative">
      <button
        ref={triggerRef}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={t('org.currentOrganization', { name: currentName })}
        className="flex w-full items-center gap-1 rounded text-left text-[11px] leading-tight text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <span className="min-w-0 flex-1 truncate">{currentName}</span>
        <ChevronsUpDown className="h-3 w-3 flex-shrink-0" aria-hidden />
      </button>
      {menu}
    </div>
  );
}
