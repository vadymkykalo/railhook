import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  ArrowLeftRight,
  Atom,
  BookOpen,
  Database,
  Github,
  History,
  Layers,
  MessageSquare,
  Scale,
  ScrollText,
  Workflow,
  Zap,
} from 'lucide-react';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { docsUrl } from '../lib/docsUrl';
import { cn } from '../lib/utils';
import { Band, FactCard, PageIntro, panel, SectionHeading } from './landing/primitives';
import { REPO_URL } from './landing/plans';

/** Facts only: no team size, logos or funding — none of them is true yet. */
const MAINTAINER_URL = 'https://github.com/vadymkykalo';

function LinkCard({
  icon: Icon,
  title,
  body,
  children,
}: {
  icon: typeof Github;
  title: string;
  body: string;
  children: (className: string, content: ReactNode) => ReactNode;
}) {
  const content = (
    <>
      <Icon className="h-4 w-4 text-primary" aria-hidden="true" />
      <span className="mt-3 block text-[15px] font-medium text-foreground">{title}</span>
      <span className="mt-2 block text-[15px] leading-relaxed text-muted-foreground">{body}</span>
    </>
  );
  return <li className="h-full">{children(cn('flex h-full flex-col p-6', panel(true)), content)}</li>;
}

export default function AboutPage() {
  const { t, i18n } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.about.title', descriptionKey: 'meta.about.description', path: '/about' });

  return (
    <>
      <PageIntro eyebrow={t('about.eyebrow')} title={t('about.title')} lead={t('about.lead')} />

      <Band labelledBy="about-why">
        <SectionHeading id="about-why" title={t('about.why.title')} />
        <div className="grid gap-4 md:grid-cols-3">
          <FactCard icon={ArrowLeftRight} title={t('about.why.both.title')}>{t('about.why.both.body')}</FactCard>
          <FactCard icon={Scale} title={t('about.why.open.title')}>{t('about.why.open.body')}</FactCard>
          <FactCard icon={ScrollText} title={t('about.why.record.title')}>{t('about.why.record.body')}</FactCard>
        </div>
      </Band>

      <Band muted labelledBy="about-who">
        <div className={cn('max-w-3xl p-6 sm:p-8', panel())}>
          <h2 id="about-who" className="text-[1.75rem] font-normal leading-[1.16] tracking-[-0.02em] sm:text-[2rem] text-foreground">
            {t('about.who.title')}
          </h2>
          <p className="mt-3 text-[1.05rem] leading-relaxed text-muted-foreground">{t('about.who.body')}</p>
          <ul className="mt-5 flex flex-wrap gap-x-6 gap-y-2">
            <li>
              <a
                href={MAINTAINER_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex min-h-10 items-center gap-2 text-sm font-medium link-ink"
              >
                <Github className="h-4 w-4" aria-hidden="true" />
                {t('about.who.github')}
              </a>
            </li>
            <li>
              <a
                href={REPO_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex min-h-10 items-center gap-2 text-sm font-medium link-ink"
              >
                {t('about.who.repo')}
              </a>
            </li>
          </ul>
        </div>
      </Band>

      <Band labelledBy="about-stack">
        <SectionHeading id="about-stack" title={t('about.stack.title')} lead={t('about.stack.lead')} />
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <FactCard icon={Layers} title={t('about.stack.java.title')}>{t('about.stack.java.body')}</FactCard>
          <FactCard icon={Database} title={t('about.stack.postgres.title')}>{t('about.stack.postgres.body')}</FactCard>
          <FactCard icon={Workflow} title={t('about.stack.kafka.title')}>{t('about.stack.kafka.body')}</FactCard>
          <FactCard icon={Zap} title={t('about.stack.redis.title')}>{t('about.stack.redis.body')}</FactCard>
          <FactCard icon={Atom} title={t('about.stack.react.title')}>{t('about.stack.react.body')}</FactCard>
        </div>
      </Band>

      <Band muted labelledBy="about-links">
        <SectionHeading id="about-links" title={t('about.links.title')} />
        <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <LinkCard icon={Github} title={t('about.links.github.title')} body={t('about.links.github.body')}>
            {(className, content) => (
              <a href={REPO_URL} target="_blank" rel="noopener noreferrer" className={className}>{content}</a>
            )}
          </LinkCard>
          <LinkCard icon={BookOpen} title={t('about.links.docs.title')} body={t('about.links.docs.body')}>
            {(className, content) => <a href={docsUrl(i18n.language)} className={className}>{content}</a>}
          </LinkCard>
          <LinkCard icon={History} title={t('about.links.changelog.title')} body={t('about.links.changelog.body')}>
            {(className, content) => (
              <a href={`${REPO_URL}/blob/main/CHANGELOG.md`} target="_blank" rel="noopener noreferrer" className={className}>{content}</a>
            )}
          </LinkCard>
          <LinkCard icon={MessageSquare} title={t('about.links.contact.title')} body={t('about.links.contact.body')}>
            {(className, content) => <Link to="/contact" className={className}>{content}</Link>}
          </LinkCard>
        </ul>
      </Band>
    </>
  );
}
