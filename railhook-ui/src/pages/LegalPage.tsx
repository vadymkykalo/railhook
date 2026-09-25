import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Reveal, Section } from './landing/primitives';
import { useDocumentMeta } from '../hooks/useDocumentMeta';

/** No markup in the translations, so a translator cannot break the page. */

type Doc = 'privacy' | 'terms';

/** Section order is part of the document, so it is written down rather than read off the JSON. */
const SECTIONS: Record<Doc, string[]> = {
  privacy: ['who', 'data', 'purpose', 'google', 'retention', 'processors', 'cookies', 'sharing', 'rights', 'changes', 'contact'],
  terms: ['service', 'free', 'use', 'suspension', 'warranty', 'liability', 'selfHosted', 'law', 'changes', 'contact'],
};

export const LEGAL_UPDATED = '2026-09-13';

function Body({ text }: { text: string }) {
  return (
    <>
      {text.split('\n\n').map((block, i) => {
        const lines = block.split('\n');
        if (lines.every((line) => line.startsWith('- '))) {
          return (
            <ul key={i} className="mt-3 list-disc space-y-1.5 pl-5">
              {lines.map((line) => <li key={line}>{line.slice(2)}</li>)}
            </ul>
          );
        }
        return <p key={i} className="mt-3">{block}</p>;
      })}
    </>
  );
}

function LegalDocument({ doc }: { doc: Doc }) {
  const { t, i18n } = useTranslation();
  useDocumentMeta({ titleKey: `meta.${doc}.title`, descriptionKey: `meta.${doc}.description`, path: `/${doc}` });
  const updated = new Date(`${LEGAL_UPDATED}T00:00:00Z`).toLocaleDateString(i18n.language, {
    year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC',
  });
  const other: Doc = doc === 'privacy' ? 'terms' : 'privacy';

  return (
    <Section ruled={false}>
      <Reveal>
        <article className="max-w-2xl">
          <h1 className="text-[2.375rem] font-normal leading-[1.16] tracking-[-0.03em] text-foreground sm:text-[3.5rem]">
            {t(`legal.${doc}.title`)}
          </h1>
          <p className="mt-3 font-mono text-xs text-muted-foreground">{t('legal.updated', { date: updated })}</p>
          <div className="mt-6 text-[15px] leading-relaxed text-muted-foreground">
            <Body text={t(`legal.${doc}.intro`)} />
          </div>

          {SECTIONS[doc].map((id) => (
            <Fragment key={id}>
              <h2 className="mt-10 text-lg font-medium tracking-tight text-foreground">
                {t(`legal.${doc}.sections.${id}.title`)}
              </h2>
              <div className="text-[15px] leading-relaxed text-muted-foreground">
                <Body text={t(`legal.${doc}.sections.${id}.body`)} />
              </div>
            </Fragment>
          ))}

          <p className="mt-12 border-t border-rail pt-6 text-sm text-muted-foreground">
            <Link to={`/${other}`} className="font-medium link-ink">
              {t(`legal.${other}.title`)}
            </Link>
          </p>
        </article>
      </Reveal>
    </Section>
  );
}

export function PrivacyPage() {
  return <LegalDocument doc="privacy" />;
}

export function TermsPage() {
  return <LegalDocument doc="terms" />;
}
