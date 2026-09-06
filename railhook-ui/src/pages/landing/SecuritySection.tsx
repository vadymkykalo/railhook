import {
  Archive, FileKey2, Fingerprint, KeyRound, Lock, RefreshCw, ScrollText, ShieldAlert, Users,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { panel, Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';

/**
 * What the page is allowed to claim about security, and nothing beyond it.
 *
 * Every item is a thing in the tree: the verifiers, the endpoint's previous
 * secret and its grace window, the per-endpoint mTLS material, the AES-GCM
 * columns and their key version, UrlValidator, @RequireAccess, @Auditable,
 * scoped API keys, GdprExportService and DataRetentionService.
 *
 * Deliberately absent: SOC 2, ISO, HIPAA, "enterprise-grade", "bank-level" and
 * SSO. There is no certification and no SSO implementation, and a security
 * section is the worst place on a landing page to be caught overstating —
 * it is read by exactly the person who will check.
 */
const ITEMS = [
  { key: 'hmac', icon: Fingerprint },
  { key: 'rotation', icon: RefreshCw },
  { key: 'mtls', icon: FileKey2 },
  { key: 'encryption', icon: Lock },
  { key: 'ssrf', icon: ShieldAlert },
  { key: 'rbac', icon: Users },
  { key: 'audit', icon: ScrollText },
  { key: 'apiKeys', icon: KeyRound },
  { key: 'gdpr', icon: Archive },
] as const;

export default function SecuritySection() {
  const { t } = useTranslation();

  return (
    <Section id="security">
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.security.eyebrow')}
          title={t('landing.security.title')}
          body={t('landing.security.body')}
        />
      </Reveal>

      <div className="mt-10 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {ITEMS.map(({ key, icon: Icon }, i) => (
          <Reveal key={key} delay={i * 50} className="h-full">
            <div className={cn('flex h-full flex-col p-5', panel(true))}>
              <Icon className="h-4 w-4 text-primary" aria-hidden="true" />
              <h3 className="mt-3 text-[15px] font-semibold text-foreground">
                {t(`landing.security.${key}Title`)}
              </h3>
              <p className="mt-2 text-[13.5px] leading-relaxed text-muted-foreground">
                {t(`landing.security.${key}Desc`)}
              </p>
            </div>
          </Reveal>
        ))}
      </div>

      <p className="mt-6 max-w-3xl text-sm text-muted-foreground">{t('landing.security.note')}</p>
    </Section>
  );
}
