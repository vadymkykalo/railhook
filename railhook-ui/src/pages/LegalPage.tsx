import { Fragment } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Reveal, Section } from './landing/primitives';
import { useDocumentMeta } from '../hooks/useDocumentMeta';

/**
 * The privacy policy and the terms of service for Railhook Cloud.
 *
 * Both are prose the owner reviews as a whole, so the text lives in the locale files section by
 * section and this component only sets it. A body is plain paragraphs separated by a blank line;
 * a paragraph whose lines start with "- " is a list. No markup in the translations, so a
 * translator cannot break the page.
 *
 * Google publishes the "Continue with Google" consent screen only with a privacy policy and terms
 * link on it, and the registration form already asks people to agree to both.
 */

type Doc = 'privacy' | 'terms';

/** Section order is part of the document, so it is written down rather than read off the JSON. */
const SECTIONS: Record<Doc, string[]> = {
  privacy: ['who', 'data', 'purpose', 'google', 'retention', 'processors', 'sharing', 'rights', 'changes', 'contact'],
  terms: ['service', 'free', 'use', 'suspension', 'warranty', 'liability', 'selfHosted', 'law', 'changes', 'contact'],
};

/** The date both documents were last changed. */
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
          <h1 className="font-display text-3xl leading-[1.1] tracking-tight text-foreground sm:text-headline">
            {t(`legal.${doc}.title`)}
          </h1>
          <p className="mt-3 font-mono text-xs text-muted-foreground">{t('legal.updated', { date: updated })}</p>
          <div className="mt-6 text-[15px] leading-relaxed text-muted-foreground">
            <Body text={t(`legal.${doc}.intro`)} />
          </div>

          {SECTIONS[doc].map((id) => (
            <Fragment key={id}>
              <h2 className="mt-10 text-lg font-semibold tracking-tight text-foreground">
                {t(`legal.${doc}.sections.${id}.title`)}
              </h2>
              <div className="text-[15px] leading-relaxed text-muted-foreground">
                <Body text={t(`legal.${doc}.sections.${id}.body`)} />
              </div>
            </Fragment>
          ))}

          <p className="mt-12 border-t border-rail pt-6 text-sm text-muted-foreground">
            <Link to={`/${other}`} className="font-medium text-primary hover:underline">
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
