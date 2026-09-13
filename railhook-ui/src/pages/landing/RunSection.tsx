import { Link } from 'react-router-dom';
import { Check, Github } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import { Band, SectionHeading } from './primitives';
import { FREE_PLAN, REPO_URL } from './plans';
import { cn } from '../../lib/utils';

/**
 * The two ways to run Railhook, as equals: the cloud, free with the seeded plan's limits, and your
 * own servers, with everything and no key. No prices — there are no paid plans to price yet, and
 * the cloud card says so in words rather than with a grid of placeholders.
 */
const CARD = 'flex scroll-mt-24 flex-col gap-3 rounded-2xl border border-rail bg-card p-6 sm:p-7';
const PILL = 'self-start rounded-full bg-accent px-2.5 py-0.5 font-mono text-[11px] uppercase tracking-[0.08em] text-primary';

export default function RunSection() {
  const { t, i18n } = useTranslation();
  const { isAuthenticated } = useAuth();
  const events = new Intl.NumberFormat(i18n.language).format(FREE_PLAN.events);

  return (
    <Band id="run" labelledBy="run-title">
      <SectionHeading id="run-title" title={t('landing.run.title')} />
      <div className="grid gap-5 md:grid-cols-2">
        {/* The easiest start carries a little more weight: the accent rail and a filled pill. */}
        <article id="cloud" className={cn(CARD, 'border-primary shadow-elevated ring-1 ring-primary')}>
          <span className="self-start rounded-full bg-primary px-2.5 py-0.5 font-mono text-[11px] uppercase tracking-[0.08em] text-primary-foreground">
            {t('landing.run.cloud.pill')}
          </span>
          <h3 className="text-[1.35rem] font-semibold tracking-[-0.01em] text-foreground">{t('landing.run.cloud.title')}</h3>
          <p className="text-muted-foreground">
            {t('landing.run.cloud.body', { events, projects: FREE_PLAN.projects, retention: FREE_PLAN.retention })}
          </p>
          <p className="text-sm text-muted-foreground">{t('landing.run.cloud.later')}</p>
          <div className="mt-auto flex flex-wrap gap-2.5 pt-3">
            <Button asChild>
              {isAuthenticated ? (
                <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
              ) : (
                <Link to="/register">{t('landing.run.cloud.cta')}</Link>
              )}
            </Button>
          </div>
        </article>

        <article id="self-host" className={CARD}>
          <span className={PILL}>{t('landing.run.selfHosted.pill')}</span>
          <h3 className="text-[1.35rem] font-semibold tracking-[-0.01em] text-foreground">{t('landing.run.selfHosted.title')}</h3>
          <p className="text-muted-foreground">{t('landing.run.selfHosted.body')}</p>
          <ul className="mt-1 grid gap-2.5">
            {[t('landing.run.selfHosted.check1'), t('landing.run.selfHosted.check2'), t('landing.run.selfHosted.check3')].map((item) => (
              <li key={item} className="grid grid-cols-[20px_1fr] gap-2.5 font-medium text-foreground">
                <Check className="mt-0.5 h-5 w-5 text-primary" strokeWidth={2.5} aria-hidden="true" />
                {item}
              </li>
            ))}
          </ul>
          <div className="mt-auto flex flex-wrap gap-2.5 pt-3">
            <Button asChild>
              <a href="#install">{t('landing.run.selfHosted.install')}</a>
            </Button>
            <Button asChild variant="outline">
              <a href={REPO_URL} target="_blank" rel="noopener noreferrer">
                <Github className="h-4 w-4" aria-hidden="true" />
                {t('landing.run.selfHosted.github')}
              </a>
            </Button>
          </div>
        </article>
      </div>
    </Band>
  );
}
