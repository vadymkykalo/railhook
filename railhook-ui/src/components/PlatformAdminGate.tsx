import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, ShieldOff } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/auth.store';
import { Button } from './ui/button';

/** Without it a pasted admin link shows an org owner load errors, not a refusal. */
export default function PlatformAdminGate({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { user } = useAuth();

  if (user?.platformAdmin) return <>{children}</>;

  return (
    <div className="flex min-h-[60vh] items-center justify-center p-4 lg:p-6">
      <div className="w-full max-w-md">
        <div className="mb-5 flex h-11 w-11 items-center justify-center border border-rail bg-card">
          <ShieldOff className="h-5 w-5 text-muted-foreground" aria-hidden />
        </div>
        <p className="mono-label">403</p>
        <h2 className="mt-1 text-title">{t('platformAdmin.denied.title')}</h2>
        <p className="mt-2 text-sm text-muted-foreground">{t('platformAdmin.denied.description')}</p>
        <Button asChild className="mt-6">
          <Link to="/admin/dashboard">
            <ArrowLeft className="h-4 w-4" aria-hidden /> {t('accessDenied.backToDashboard')}
          </Link>
        </Button>
      </div>
    </div>
  );
}
