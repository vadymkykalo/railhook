import { Suspense, lazy, useCallback, useEffect, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowRight, ExternalLink, Menu, X } from 'lucide-react';
import { RailhookIcon } from '../components/icons/RailhookIcon';
import ThemeToggle from '../components/ThemeToggle';
import LanguageSwitcher from '../components/LanguageSwitcher';
import { Skeleton } from '../components/ui/skeleton';
import { cn } from '../lib/utils';
import { CANONICAL_REFERENCE_URL } from './docs/apiIndex';
import type { SampleLanguage } from './docs/primitives';
import {
  GUIDE_SECTIONS, REFERENCE_SECTION, resolveAnchor, resolveSectionId, sectionPath, type SectionId,
} from './docs/sections';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { Footer } from '../layout/PublicLayout';

import Alerts from './docs/guides/Alerts';
import Authentication from './docs/guides/Authentication';
import Cli from './docs/guides/Cli';
import Configuration from './docs/guides/Configuration';
import DeterministicReplay from './docs/guides/DeterministicReplay';
import EndpointSecurity from './docs/guides/EndpointSecurity';
import ErrorsAndLimits from './docs/guides/ErrorsAndLimits';
import GettingStarted from './docs/guides/GettingStarted';
import IncomingWebhooks from './docs/guides/IncomingWebhooks';
import Ordering from './docs/guides/Ordering';
import Overview from './docs/guides/Overview';
import PiiMasking from './docs/guides/PiiMasking';
import Retries from './docs/guides/Retries';
import RulesEngine from './docs/guides/RulesEngine';
import SchemaRegistry from './docs/guides/SchemaRegistry';
import Sdks from './docs/guides/Sdks';
import Transformations from './docs/guides/Transformations';
import WebhookSecurity from './docs/guides/WebhookSecurity';
import Workflows from './docs/guides/Workflows';

/**
 * The docs shell.
 *
 * This file used to be 4,087 lines, most of them a hand-copied restatement of
 * `openapi.yaml`: every endpoint, parameter table and response example lived
 * here and drifted the moment the API changed. Those are now generated — see
 * `docs/ApiReference.tsx` and `scripts/generate-docs-api-index.mjs`. What is
 * left in `docs/guides/` is the part a spec cannot express: why the thing works
 * the way it does, and what to do about it.
 */

/** Separate chunk: the reference pulls in the ~160 KB generated spec index. */
const ApiReference = lazy(() => import('./docs/ApiReference'));

export default function DocumentationPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const params = useParams<{ sectionId?: string }>();

  /* A guide is addressed by path now. The hash is still read, because every
     link written before this — in an EmptyState, a bookmark, an old blog post —
     is `/docs#retries`. */
  const routed = resolveSectionId(params.sectionId);
  const initial = routed ? { section: routed } : resolveAnchor(location.hash);

  const [section, setSection] = useState<SectionId>(initial.section);
  const [referenceGroup, setReferenceGroup] = useState<string | undefined>(initial.group);
  const [language, setLanguage] = useState<SampleLanguage>('curl');
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    if (routed) {
      setSection(routed);
      return;
    }
    if (!location.hash) {
      setSection('overview');
      return;
    }
    const resolved = resolveAnchor(location.hash);
    setSection(resolved.section);
    if (resolved.group) setReferenceGroup(resolved.group);
  }, [location.hash, routed]);

  const go = useCallback(
    (next: SectionId) => {
      setSection(next);
      setMobileNavOpen(false);
      navigate(sectionPath(next), { replace: false });
      window.scrollTo({ top: 0, behavior: 'auto' });
    },
    [navigate]
  );

  const sampleProps = { language, onLanguageChange: setLanguage };

  /* Each guide has its own URL now, so each needs its own title: thirteen pages sharing
     "Documentation — Railhook" is one page as far as a search result is concerned. The
     section's own sidebar label is the name it is already known by. */
  const sectionLabel = [...GUIDE_SECTIONS, REFERENCE_SECTION].find((s) => s.id === section)?.labelKey;
  useDocumentMeta({
    titleKey: 'meta.docs.title',
    titleParams: section === 'overview' || !sectionLabel ? undefined : { section: t(sectionLabel) },
    descriptionKey: 'meta.docs.description',
    path: sectionPath(section),
  });

  return (
    <div className="min-h-screen bg-background">
      <div className="flex">
        <DocsNav section={section} onSelect={go} mobileOpen={mobileNavOpen} onMobileClose={() => setMobileNavOpen(false)} />

        <main className="min-w-0 flex-1 lg:pl-64">
          <div className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b border-rail bg-background/90 px-4 glass lg:hidden">
            <button
              type="button"
              onClick={() => setMobileNavOpen(true)}
              aria-label={t('nav.openMenu')}
              className="rounded-md p-1.5 hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <Menu className="h-5 w-5" aria-hidden />
            </button>
            <span className="flex-1 truncate text-sm font-medium">{t('docsPage.mobileTitle')}</span>
            <LanguageSwitcher />
            <ThemeToggle variant="icon" />
          </div>

          <div className="mx-auto max-w-3xl px-4 py-10 sm:px-6 lg:px-8">
            {section === 'overview' && <Overview />}
            {section === 'getting-started' && <GettingStarted {...sampleProps} />}
            {section === 'configuration' && <Configuration />}
            {section === 'authentication' && <Authentication {...sampleProps} />}
            {section === 'webhook-security' && <WebhookSecurity {...sampleProps} />}
            {section === 'retries' && <Retries />}
            {section === 'incoming-webhooks' && <IncomingWebhooks />}
            {section === 'rules-engine' && <RulesEngine />}
            {section === 'transformations' && <Transformations />}
            {section === 'ordering' && <Ordering />}
            {section === 'schema-registry' && <SchemaRegistry />}
            {section === 'deterministic-replay' && <DeterministicReplay />}
            {section === 'workflows' && <Workflows />}
            {section === 'endpoint-security' && <EndpointSecurity />}
            {section === 'pii-masking' && <PiiMasking />}
            {section === 'alerts' && <Alerts />}
            {section === 'errors' && <ErrorsAndLimits />}
            {section === 'cli' && <Cli />}
            {section === 'sdks' && <Sdks />}
            {section === 'api-reference' && (
              <Suspense fallback={<ReferenceSkeleton />}>
                <ApiReference group={referenceGroup} onGroupChange={setReferenceGroup} />
              </Suspense>
            )}
          </div>

          {/* Inside the content column, not around the page: the sidebar beside
              it is `fixed`, so a footer at layout level lays out under it. The
              docs used to have no footer at all — the deepest page in the funnel
              was the one with no link back to pricing or a signup. */}
          <Footer />
        </main>
      </div>
    </div>
  );
}

function ReferenceSkeleton() {
  return (
    <div className="space-y-4" aria-busy>
      <Skeleton className="h-9 w-64" />
      <Skeleton className="h-5 w-full max-w-lg" />
      <Skeleton className="h-32 w-full" />
    </div>
  );
}

function DocsNav({
  section,
  onSelect,
  mobileOpen,
  onMobileClose,
}: {
  section: SectionId;
  onSelect: (section: SectionId) => void;
  mobileOpen: boolean;
  onMobileClose: () => void;
}) {
  const { t } = useTranslation();

  const item = (meta: (typeof GUIDE_SECTIONS)[number]) => (
    <li key={meta.id}>
      <button
        type="button"
        onClick={() => onSelect(meta.id)}
        aria-current={section === meta.id ? 'page' : undefined}
        className={cn(
          'flex w-full items-center gap-2.5 rounded-md px-3 py-1.5 text-[13px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          section === meta.id
            ? 'bg-primary/10 font-medium text-primary'
            : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
        )}
      >
        <meta.icon className="h-4 w-4 flex-shrink-0" aria-hidden />
        <span className="truncate">{t(meta.labelKey)}</span>
      </button>
    </li>
  );

  const content = (
    <div className="p-5">
      <Link
        to="/"
        className="mb-8 flex items-center gap-2.5 rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        <div className="flex h-8 w-8 items-center justify-center rounded-md bg-primary">
          <RailhookIcon className="h-4 w-4 text-primary-foreground" />
        </div>
        <span className="text-base font-semibold tracking-tight">Railhook</span>
      </Link>

      <p className="mono-label mb-2 px-3">{t('docsPage.sidebar.guides')}</p>
      <ul className="space-y-0.5">{GUIDE_SECTIONS.map(item)}</ul>

      <p className="mono-label mb-2 mt-6 px-3">{t('docsPage.sidebar.reference')}</p>
      <ul className="space-y-0.5">{item(REFERENCE_SECTION)}</ul>

      <div className="mt-8 space-y-1 border-t border-rail pt-6">
        <a
          href={CANONICAL_REFERENCE_URL}
          target="_blank"
          rel="noopener noreferrer"
          className="flex items-center gap-2 rounded-md px-3 py-1.5 text-[13px] text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ExternalLink className="h-4 w-4" aria-hidden />
          {t('docsPage.sidebar.publishedSpec')}
        </a>
        <Link
          to="/admin/dashboard"
          className="flex items-center gap-2 rounded-md px-3 py-1.5 text-[13px] text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ArrowRight className="h-4 w-4" aria-hidden />
          {t('docsPage.sidebar.goToDashboard')}
        </Link>
        <LanguageSwitcher variant="full" />
        <ThemeToggle variant="full" />
      </div>
    </div>
  );

  return (
    <>
      <aside className="fixed left-0 top-0 hidden h-screen w-64 overflow-y-auto border-r border-rail bg-card/40 lg:block">
        <nav aria-label={t('docsPage.sidebar.navigation')}>{content}</nav>
      </aside>

      {mobileOpen && (
        <div className="fixed inset-0 z-50 lg:hidden">
          <div
            className="fixed inset-0 bg-foreground/30"
            onClick={onMobileClose}
            role="presentation"
          />
          <aside className="fixed inset-y-0 left-0 w-72 overflow-y-auto border-r border-rail bg-card shadow-elevated">
            <div className="flex items-center justify-between border-b border-rail p-4">
              <span className="text-sm font-medium">{t('docsPage.sidebar.navigation')}</span>
              <button
                type="button"
                onClick={onMobileClose}
                aria-label={t('nav.closeMenu')}
                className="rounded-md p-1.5 hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <X className="h-4 w-4" aria-hidden />
              </button>
            </div>
            <nav aria-label={t('docsPage.sidebar.navigation')}>{content}</nav>
          </aside>
        </div>
      )}
    </>
  );
}
