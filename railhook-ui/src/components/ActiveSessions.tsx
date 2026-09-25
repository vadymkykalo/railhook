import { useState } from 'react';
import { Loader2, Monitor, Terminal } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useSessions, useRevokeSession, useRevokeAllSessions } from '../api/queries';
import { useAuth } from '../auth/auth.store';
import { formatDateTime, formatRelativeTime } from '../lib/date';
import { showApiError, showSuccess } from '../lib/toast';
import ConfirmDialog from './ConfirmDialog';
import { Button } from './ui/button';
import type { SessionResponse } from '../api/auth.api';

/** The client is named on every row: CLI grants outlive their machines far more often than tabs. */
export default function ActiveSessions() {
  const { t } = useTranslation();
  const { logout } = useAuth();
  const { data: sessions = [], isLoading } = useSessions();
  const revokeSession = useRevokeSession();
  const revokeAll = useRevokeAllSessions();
  const [pendingRevoke, setPendingRevoke] = useState<SessionResponse | null>(null);
  const [confirmRevokeAll, setConfirmRevokeAll] = useState(false);

  const handleRevoke = () => {
    if (!pendingRevoke) return;
    const wasCurrent = pendingRevoke.current;
    revokeSession.mutate(pendingRevoke.id, {
      onSuccess: () => {
        setPendingRevoke(null);
        showSuccess(t('settings.sessions.revoked'));
        // The tab's token is now refused, so go to login rather than fail request by request.
        if (wasCurrent) logout();
      },
      onError: (error) => showApiError(error, 'settings.sessions.revokeFailed'),
    });
  };

  const handleRevokeAll = () => {
    revokeAll.mutate(undefined, {
      onSuccess: () => {
        setConfirmRevokeAll(false);
        logout();
      },
      onError: (error) => showApiError(error, 'settings.sessions.revokeFailed'),
    });
  };

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
        {t('common.loading')}
      </div>
    );
  }

  return (
    <>
      <ul className="divide-y divide-rail">
        {sessions.map((session) => {
          const Icon = session.client === 'CLI' ? Terminal : Monitor;
          return (
            <li key={session.id} className="flex items-start gap-3 py-3 first:pt-0">
              <Icon className="mt-0.5 h-4 w-4 flex-shrink-0 text-muted-foreground" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="flex flex-wrap items-center gap-2 text-sm font-medium">
                  {t(`settings.sessions.client.${session.client}`)}
                  {session.current && (
                    <span className="rounded bg-primary/15 px-1.5 py-0.5 text-[11px] font-normal text-primary">
                      {t('settings.sessions.thisDevice')}
                    </span>
                  )}
                </p>
                {session.userAgent && (
                  <p className="truncate text-xs text-muted-foreground" title={session.userAgent}>
                    {session.userAgent}
                  </p>
                )}
                <p className="text-xs text-muted-foreground">
                  {t('settings.sessions.meta', {
                    ip: session.ipAddress || t('settings.sessions.unknownIp'),
                    lastSeen: formatRelativeTime(session.lastSeenAt),
                  })}
                </p>
                <p className="text-xs text-muted-foreground">
                  {t('settings.sessions.signedInAt', { at: formatDateTime(session.createdAt) })}
                </p>
              </div>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setPendingRevoke(session)}
                className="flex-shrink-0 text-muted-foreground hover:text-halt"
              >
                {t('settings.sessions.revoke')}
              </Button>
            </li>
          );
        })}
      </ul>

      {sessions.length > 1 && (
        <div className="flex justify-end border-t border-rail pt-4">
          <Button variant="outline" size="sm" onClick={() => setConfirmRevokeAll(true)}>
            {t('settings.sessions.revokeAll')}
          </Button>
        </div>
      )}

      <ConfirmDialog
        open={!!pendingRevoke}
        onOpenChange={(open) => !open && setPendingRevoke(null)}
        title={t('settings.sessions.revokeTitle')}
        description={
          pendingRevoke?.current
            ? t('settings.sessions.revokeCurrentDesc')
            : t('settings.sessions.revokeDesc')
        }
        confirmLabel={t('settings.sessions.revoke')}
        loading={revokeSession.isPending}
        onConfirm={handleRevoke}
      />

      <ConfirmDialog
        open={confirmRevokeAll}
        onOpenChange={setConfirmRevokeAll}
        title={t('settings.sessions.revokeAllTitle')}
        description={t('settings.sessions.revokeAllDesc')}
        confirmLabel={t('settings.sessions.revokeAll')}
        loading={revokeAll.isPending}
        onConfirm={handleRevokeAll}
      />
    </>
  );
}
