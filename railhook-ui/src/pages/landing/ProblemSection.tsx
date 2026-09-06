import { Check, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { panel, Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';

/**
 * The only section that argues rather than describes.
 *
 * Everything else on this page assumes the reader has already decided they want
 * webhook infrastructure and is choosing a supplier. Most of them have not: they
 * are about to write it themselves, because the first version is one HTTP POST
 * and looks like an afternoon. The seven rows are the seven things that turn
 * that afternoon into a quarter, each one a feature further down the page — so
 * the argument and the proof are the same list read twice.
 *
 * No hours-saved number. The version of this that shipped elsewhere claimed
 * "200+ engineering hours per year", which nobody measured and every reader
 * discounts to zero along with the sentence around it.
 */
const ROWS = [1, 2, 3, 4, 5, 6, 7] as const;

export default function ProblemSection() {
  const { t } = useTranslation();

  return (
    <Section id="why">
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.problem.eyebrow')}
          title={t('landing.problem.title')}
          body={t('landing.problem.body')}
        />
      </Reveal>

      <div className="mt-10 grid gap-5 md:grid-cols-2">
        <Reveal className="h-full">
          <div className={cn('flex h-full flex-col p-6', panel())}>
            <p className="mono-label">{t('landing.problem.before')}</p>
            <ul className="mt-5 space-y-3.5">
              {ROWS.map((n) => (
                <li key={n} className="flex gap-3 text-[15px] leading-relaxed text-muted-foreground">
                  <X className="mt-1 h-3.5 w-3.5 shrink-0 text-halt" aria-hidden="true" />
                  {t(`landing.problem.item${n}Before`)}
                </li>
              ))}
            </ul>
          </div>
        </Reveal>

        <Reveal className="h-full" delay={90}>
          <div className={cn('flex h-full flex-col p-6', panel(true))}>
            <p className="mono-label text-primary">{t('landing.problem.after')}</p>
            <ul className="mt-5 space-y-3.5">
              {ROWS.map((n) => (
                <li key={n} className="flex gap-3 text-[15px] leading-relaxed text-foreground">
                  <Check className="mt-1 h-3.5 w-3.5 shrink-0 text-ok" aria-hidden="true" />
                  {t(`landing.problem.item${n}After`)}
                </li>
              ))}
            </ul>
          </div>
        </Reveal>
      </div>

      <p className="mt-6 max-w-3xl text-sm text-muted-foreground">{t('landing.problem.note')}</p>
    </Section>
  );
}
