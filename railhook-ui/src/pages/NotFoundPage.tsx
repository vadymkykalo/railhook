import { Link, useLocation, useNavigate } from 'react-router-dom';
import { ArrowLeft, Compass } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../components/ui/button';

/**
 * Reached from inside the admin shell and from the open web, so it never
 * renders an h1 the layout would duplicate, and it names the path that missed.
 */
export default function NotFoundPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const isLoggedIn = !!localStorage.getItem('auth_token');

  return (
    <div className="flex min-h-[60vh] items-center justify-center p-4 lg:p-6">
      <div className="w-full max-w-md">
        <div className="mb-5 flex h-11 w-11 items-center justify-center rounded-lg border border-rail bg-card">
          <Compass className="h-5 w-5 text-muted-foreground" aria-hidden />
        </div>
        <p className="mono-label">404</p>
        <h2 className="mt-1 text-title">{t('notFound.title')}</h2>
        <p className="mt-2 text-sm text-muted-foreground">{t('notFound.description')}</p>
        <p className="mt-3 overflow-x-auto rounded-md border border-rail bg-secondary/60 px-3 py-2 font-mono text-[12px] text-muted-foreground">
          {location.pathname}
        </p>

        <div className="mt-6 flex flex-wrap items-center gap-2">
          <Button asChild>
            <Link to={isLoggedIn ? '/admin/dashboard' : '/'}>
              <ArrowLeft className="h-4 w-4" aria-hidden />
              {isLoggedIn ? t('notFound.backToDashboard') : t('notFound.backToHome')}
            </Link>
          </Button>
          <Button variant="outline" onClick={() => navigate(-1)}>
            {t('notFound.goBack')}
          </Button>
        </div>
      </div>
    </div>
  );
}
