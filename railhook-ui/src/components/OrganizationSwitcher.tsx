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

/** Rendered only with more than one organization. */
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
      // The token must be set before /auth/me, or it answers for the organization being left.
      http.setToken(accessToken);
      const me = await authApi.getCurrentUser();
      login(accessToken, me);
      // Every cached query is org-scoped; selective invalidation would leak the old org's rows.
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
        'absolute bottom-full z-50 mb-1 overflow-hidden border border-rail bg-popover shadow-elevated animate-scale-in',
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
                  'flex w-full items-center gap-2.5 px-2 py-1.5 text-left text-[13px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60',
                  current
                    ? 'bg-accent font-medium text-accent-foreground'
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
