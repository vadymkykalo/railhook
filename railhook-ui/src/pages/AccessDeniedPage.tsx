import { Link } from 'react-router-dom';
import { ArrowLeft, ShieldOff } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { usePermissions } from '../auth/usePermissions';
import { RoleCard } from '../components/PermissionGate';
import { Button } from '../components/ui/button';

/**
 * Rendered by ProtectedRoute when a role is not enough for a route. It says
 * which role the reader holds and what that role can do, because "access
 * denied" on its own leaves a person with nowhere to go.
 */
export default function AccessDeniedPage() {
  const { t } = useTranslation();
  const { role } = usePermissions();

  return (
    <div className="flex min-h-[60vh] items-center justify-center p-4 lg:p-6">
      <div className="w-full max-w-md">
        <div className="mb-5 flex h-11 w-11 items-center justify-center rounded-lg border border-rail bg-card">
          <ShieldOff className="h-5 w-5 text-muted-foreground" aria-hidden />
        </div>
        <p className="mono-label">403</p>
        <h2 className="mt-1 text-title">{t('accessDenied.title')}</h2>
        <p className="mt-2 text-sm text-muted-foreground">{t('accessDenied.description')}</p>

        <div className="mt-5">
          <p className="mono-label mb-2">{t('accessDenied.yourRoleLabel')}</p>
          <RoleCard role={role} />
        </div>

        <div className="mt-6 flex flex-wrap items-center gap-3">
          <Button asChild>
            <Link to="/admin/dashboard">
              <ArrowLeft className="h-4 w-4" aria-hidden /> {t('accessDenied.backToDashboard')}
            </Link>
          </Button>
          <span className="text-[13px] text-muted-foreground">{t('permissions.contactOwner')}</span>
        </div>
      </div>
    </div>
  );
}
