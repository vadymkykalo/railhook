import { useState } from 'react';
import { Plus } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';

/**
 * The objections, answered in the reader's words rather than the domain's.
 *
 * Ordered by what actually stops a signup: what the free plan really contains,
 * then what happens when things break, then the two questions that decide
 * whether this is a lock-in — moving to self-hosted, and hitting a limit.
 *
 * Native <details> rather than a hand-rolled accordion: it opens without
 * JavaScript, it is findable by the browser's own in-page search when closed in
 * browsers that support it, and it needs no aria wiring to be announced right.
 */
const QUESTIONS = [1, 2, 3, 4, 5, 6, 7, 8] as const;

export default function FaqSection() {
  const { t } = useTranslation();
  const [open, setOpen] = useState<number | null>(1);

  return (
    <Section id="faq">
      <Reveal>
        <SectionHeader eyebrow={t('landing.faq.eyebrow')} title={t('landing.faq.title')} />
      </Reveal>

      <div className="mt-10 max-w-3xl">
        {QUESTIONS.map((n) => (
          <details
            key={n}
            open={open === n}
            onToggle={(e) => {
              if (e.currentTarget.open) setOpen(n);
              else if (open === n) setOpen(null);
            }}
            className="group border-t border-rail last:border-b"
          >
            <summary className="flex cursor-pointer list-none items-start justify-between gap-6 py-4 text-[15px] font-medium text-foreground hover:text-primary [&::-webkit-details-marker]:hidden">
              {t(`landing.faq.q${n}`)}
              <Plus
                className={cn(
                  'mt-1 h-4 w-4 shrink-0 text-muted-foreground transition-transform duration-200',
                  'group-open:rotate-45 motion-reduce:transition-none',
                )}
                aria-hidden="true"
              />
            </summary>
            <p className="max-w-2xl pb-5 text-[15px] leading-relaxed text-muted-foreground">
              {t(`landing.faq.a${n}`)}
            </p>
          </details>
        ))}
      </div>
    </Section>
  );
}
