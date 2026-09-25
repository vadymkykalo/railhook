import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  Ban,
  BadgeCheck,
  Building2,
  Clock,
  Cloud,
  Download,
  EyeOff,
  Gauge,
  Github,
  Hash,
  HardDrive,
  History,
  KeyRound,
  Lock,
  Mail,
  Network,
  PenLine,
  ScrollText,
  Server,
  ShieldCheck,
  Users,
} from 'lucide-react';
import { Button } from '../components/ui/button';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { contactDomain } from '../lib/runtimeConfig';
import { Band, FactCard, PageIntro, SectionHeading } from './landing/primitives';
import { FREE_PLAN, REPO_URL } from './landing/plans';

/** No certification is named because there is none. */
function Grid({ children, columns = 'sm:grid-cols-2' }: { children: React.ReactNode; columns?: string }) {
  return <div className={`grid gap-4 ${columns}`}>{children}</div>;
}

export default function SecurityPage() {
  const { t } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.security.title', descriptionKey: 'meta.security.description', path: '/security' });
  const domain = contactDomain();

  return (
    <>
      <PageIntro eyebrow={t('security.eyebrow')} title={t('security.title')} lead={t('security.lead')}>
        <Button asChild variant="outline" className="max-sm:w-full">
          <a href={REPO_URL} target="_blank" rel="noopener noreferrer">
            <Github className="h-4 w-4" aria-hidden="true" />
            {t('security.sourceCta')}
          </a>
        </Button>
      </PageIntro>

      <Band labelledBy="security-hosting">
        <SectionHeading id="security-hosting" title={t('security.hosting.title')} lead={t('security.hosting.lead')} />
        <Grid columns="sm:grid-cols-2 lg:grid-cols-4">
          <FactCard icon={Server} title={t('security.hosting.hetzner.title')}>{t('security.hosting.hetzner.body')}</FactCard>
          <FactCard icon={Cloud} title={t('security.hosting.cloudflare.title')}>{t('security.hosting.cloudflare.body')}</FactCard>
          <FactCard icon={Mail} title={t('security.hosting.resend.title')}>{t('security.hosting.resend.body')}</FactCard>
          <FactCard icon={HardDrive} title={t('security.hosting.selfHosted.title')}>{t('security.hosting.selfHosted.body')}</FactCard>
        </Grid>
      </Band>

      <Band muted labelledBy="security-secrets">
        <SectionHeading id="security-secrets" title={t('security.secrets.title')} lead={t('security.secrets.lead')} />
        <Grid>
          <FactCard icon={Lock} title={t('security.secrets.signing.title')}>{t('security.secrets.signing.body')}</FactCard>
          <FactCard icon={Hash} title={t('security.secrets.apiKeys.title')}>{t('security.secrets.apiKeys.body')}</FactCard>
          <FactCard icon={KeyRound} title={t('security.secrets.passwords.title')}>{t('security.secrets.passwords.body')}</FactCard>
          <FactCard icon={History} title={t('security.secrets.sessions.title')}>{t('security.secrets.sessions.body')}</FactCard>
        </Grid>
      </Band>

      <Band labelledBy="security-access">
        <SectionHeading id="security-access" title={t('security.access.title')} />
        <Grid>
          <FactCard icon={Building2} title={t('security.access.tenancy.title')}>{t('security.access.tenancy.body')}</FactCard>
          <FactCard icon={Users} title={t('security.access.roles.title')}>{t('security.access.roles.body')}</FactCard>
          <FactCard icon={ScrollText} title={t('security.access.audit.title')}>{t('security.access.audit.body')}</FactCard>
          <FactCard icon={Gauge} title={t('security.access.abuse.title')}>{t('security.access.abuse.body')}</FactCard>
        </Grid>
      </Band>

      <Band muted labelledBy="security-webhooks">
        <SectionHeading id="security-webhooks" title={t('security.webhooks.title')} />
        <Grid columns="sm:grid-cols-2 lg:grid-cols-3">
          <FactCard icon={PenLine} title={t('security.webhooks.signed.title')}>{t('security.webhooks.signed.body')}</FactCard>
          <FactCard icon={ShieldCheck} title={t('security.webhooks.incoming.title')}>{t('security.webhooks.incoming.body')}</FactCard>
          <FactCard icon={Network} title={t('security.webhooks.network.title')}>{t('security.webhooks.network.body')}</FactCard>
          <FactCard icon={BadgeCheck} title={t('security.webhooks.mtls.title')}>{t('security.webhooks.mtls.body')}</FactCard>
          <FactCard icon={EyeOff} title={t('security.webhooks.pii.title')}>{t('security.webhooks.pii.body')}</FactCard>
        </Grid>
      </Band>

      <Band labelledBy="security-data">
        <SectionHeading id="security-data" title={t('security.data.title')} />
        <Grid columns="md:grid-cols-3">
          <FactCard icon={Clock} title={t('security.data.retention.title')}>
            {t('security.data.retention.body', { days: FREE_PLAN.retention })}
          </FactCard>
          <FactCard icon={Download} title={t('security.data.export.title')}>{t('security.data.export.body')}</FactCard>
          <FactCard icon={Ban} title={t('security.data.noSale.title')}>{t('security.data.noSale.body')}</FactCard>
        </Grid>
        <p className="mt-6">
          <Link to="/privacy" className="text-sm font-medium link-ink">
            {t('security.data.privacyCta')}
          </Link>
        </p>
      </Band>

      <Band muted labelledBy="security-report">
        <div className="max-w-2xl">
          <h2 id="security-report" className="text-[1.75rem] font-normal leading-[1.16] tracking-[-0.02em] sm:text-[2rem] text-foreground">
            {t('security.report.title')}
          </h2>
          <p className="mt-2 text-muted-foreground">{t('security.report.body')}</p>
          <div className="mt-6 flex flex-wrap gap-2.5">
            <Button asChild className="max-sm:w-full">
              <a href={`${REPO_URL}/security/advisories/new`} target="_blank" rel="noopener noreferrer">
                {t('security.report.advisory')}
              </a>
            </Button>
            <Button asChild variant="outline" className="max-sm:w-full">
              <a href={`${REPO_URL}/blob/main/SECURITY.md`} target="_blank" rel="noopener noreferrer">
                {t('security.report.policy')}
              </a>
            </Button>
          </div>
          {/* Only where the deployment has a support desk, as on the contact page. */}
          {domain && (
            <p className="mt-4 text-sm text-muted-foreground">
              {t('security.report.email')}{' '}
              <a href={`mailto:support@${domain}`} className="font-medium link-ink">
                {`support@${domain}`}
              </a>
            </p>
          )}
        </div>
      </Band>
    </>
  );
}
