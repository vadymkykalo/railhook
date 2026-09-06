import { useTranslation } from 'react-i18next';
import AttemptRail from '../../components/AttemptRail';
import { panel, Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';

/**
 * The ladder, drawn at full size with the same component the admin uses.
 *
 * The numbers come from RetryLadderDefaults: outgoing waits 1m, 5m, 15m, 1h,
 * 6h, 24h across seven attempts; incoming stops at five. Nothing here is a
 * round marketing number.
 */

function Fact({ title, body }: { title: string; body: string }) {
  return (
    <div className="border-t border-rail py-5">
      <h3 className="text-[15px] font-semibold text-foreground">{title}</h3>
      <p className="mt-1.5 text-[15px] leading-relaxed text-muted-foreground">{body}</p>
    </div>
  );
}

export default function ReliabilitySection() {
  const { t } = useTranslation();
  return (
    <Section id="retries">
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.reliability.eyebrow')}
          title={t('landing.reliability.title')}
          body={t('landing.reliability.body')}
        />
      </Reveal>

      <div className="mt-10 grid items-start gap-10 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)] lg:gap-14">
        <Reveal>
          <Fact title={t('landing.reliability.dlqTitle')} body={t('landing.reliability.dlqBody')} />
          <Fact title={t('landing.reliability.replayTitle')} body={t('landing.reliability.replayBody')} />
          <Fact title={t('landing.reliability.orderTitle')} body={t('landing.reliability.orderBody')} />
        </Reveal>

        <Reveal delay={90} className={cn('flex flex-col p-5 sm:p-6', panel())}>
          <p className="mono-label">{t('landing.reliability.ladderLabel')}</p>
          <div className="mt-7">
            <AttemptRail attempts={[]} maxAttempts={7} size="full" ariaLabel={t('landing.reliability.ladderAria')} />
          </div>
          <p className="mt-6 text-sm leading-relaxed text-muted-foreground">{t('landing.reliability.ladderNote')}</p>
          <dl className="mt-6 grid grid-cols-[auto_minmax(0,1fr)] gap-x-5 gap-y-1.5 border-t border-rail pt-4 font-mono text-[11.5px]">
            <dt className="text-muted-foreground">outgoing</dt>
            <dd className="text-foreground">1m · 5m · 15m · 1h · 6h · 24h — 7 attempts</dd>
            <dt className="text-muted-foreground">incoming</dt>
            <dd className="text-foreground">1m · 5m · 15m · 1h · 6h — 5 attempts</dd>
          </dl>
          <p className="mt-4 font-mono text-[11.5px] text-halt">{t('landing.reliability.ladderEnd')}</p>
        </Reveal>
      </div>
    </Section>
  );
}
