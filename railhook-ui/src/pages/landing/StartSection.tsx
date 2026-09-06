import { Link } from 'react-router-dom';
import { ArrowRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { CodeBlock, LogoMark, Reveal, Section, SectionHeader } from './primitives';
import { quickstartSamples } from '../docs/samples';

/**
 * The three requests that get a webhook out the door, and nothing else.
 *
 * The samples are imported from the docs rather than retyped, so the page a
 * buyer reads and the guide they follow ten minutes later cannot disagree about
 * the shape of a request. The third step has no sample on purpose — watching
 * the attempts happens in the dashboard, which the section above already showed.
 */
const CLIENTS = [
  { name: 'Node.js', src: '/logos/nodejs.svg' },
  { name: 'TypeScript', src: '/logos/typescript.svg' },
  { name: 'Python', src: '/logos/python.svg' },
  { name: 'PHP', src: '/logos/php.svg' },
];

const STEPS = [
  { key: 'step1', sample: quickstartSamples.endpoint, label: 'bash' },
  { key: 'step2', sample: quickstartSamples.event, label: 'bash' },
  { key: 'step3', sample: null, label: null },
] as const;

export default function StartSection() {
  const { t } = useTranslation();

  return (
    <Section id="start">
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.start.eyebrow')}
          title={t('landing.start.title')}
          body={t('landing.start.body')}
        />
      </Reveal>

      <ol className="mt-10 space-y-5">
        {STEPS.map(({ key, sample, label }, i) => (
          <Reveal key={key} delay={i * 70}>
            <li className="grid gap-4 border-t border-rail pt-5 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)] lg:gap-10">
              <div>
                <p className="font-mono text-[11.5px] text-primary">{`0${i + 1}`}</p>
                <h3 className="mt-2 text-[15px] font-semibold text-foreground">
                  {t(`landing.start.${key}Title`)}
                </h3>
                <p className="mt-1.5 text-[15px] leading-relaxed text-muted-foreground">
                  {t(`landing.start.${key}Body`)}
                </p>
              </div>
              {sample && <CodeBlock code={sample} label={label ?? undefined} wrap />}
            </li>
          </Reveal>
        ))}
      </ol>

      <Reveal>
        <div className="mt-8 flex flex-col gap-4 border-t border-rail pt-6 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <ul className="flex flex-wrap items-center gap-x-6 gap-y-3">
              {CLIENTS.map((mark) => (
                <li key={mark.name} className="flex items-center gap-2.5">
                  <LogoMark src={mark.src} name={mark.name} className="h-5 w-5" />
                  <span className="text-sm text-foreground">{mark.name}</span>
                </li>
              ))}
            </ul>
            <p className="mt-3 font-mono text-[11.5px] text-muted-foreground">{t('landing.start.sdkNote')}</p>
          </div>
          <Link
            to="/docs/getting-started"
            className="inline-flex shrink-0 items-center gap-1.5 text-sm font-medium text-primary hover:underline"
          >
            {t('landing.start.docsLink')} <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
          </Link>
        </div>
      </Reveal>
    </Section>
  );
}
