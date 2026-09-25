import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Trans, useTranslation } from 'react-i18next';
import { Button } from '../../components/ui/button';
import { useAuth } from '../../auth/auth.store';
import { cn } from '../../lib/utils';
import { prefersReducedMotion } from './primitives';
import { Chevron, IN, Node, RICH, Wire } from './parts';

type Row = { at: string; outcome: string; wait?: { n: number; unit: 'minutes' | 'hours' } };

const ROWS: Row[] = [
  { at: '14:02:07', outcome: '503 Service Unavailable', wait: { n: 1, unit: 'minutes' } },
  { at: '14:03:07', outcome: '503 Service Unavailable', wait: { n: 5, unit: 'minutes' } },
  { at: '14:08:07', outcome: 'connection refused', wait: { n: 15, unit: 'minutes' } },
  { at: '14:23:07', outcome: '502 Bad Gateway', wait: { n: 1, unit: 'hours' } },
  { at: '15:23:07', outcome: 'ok' },
];
const LAST = ROWS.length - 1;

export default function HeroSection() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  return (
    <section aria-labelledby="hero-title" className={cn(IN, 'pt-16 min-[901px]:pt-[110px]')}>
      <h1
        id="hero-title"
        className="text-[38px] font-normal leading-[1.16] tracking-[-0.03em] text-foreground md:text-[56px]"
      >
        <Trans i18nKey="landing.hero.title" components={RICH} />
      </h1>
      <div className="mt-8 flex flex-col items-start gap-10 min-[901px]:mt-16 min-[901px]:flex-row min-[901px]:items-end min-[901px]:justify-between">
        <p className="max-w-[560px] text-base leading-normal text-[#333] dark:text-muted-foreground">{t('landing.hero.lead')}</p>
        <div className="flex flex-wrap gap-4">
          <Button asChild size="lg">
            {isAuthenticated ? (
              <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
            ) : (
              <Link to="/register">{t('landing.hero.startFree')}</Link>
            )}
          </Button>
          <Button asChild size="lg" variant="outline">
            <a href="#self-host">
              <Chevron />
              {t('landing.hero.selfHost')}
            </a>
          </Button>
        </div>
      </div>
      <Stage />
    </section>
  );
}

function Stage() {
  const { t } = useTranslation();
  const ref = useRef<HTMLDivElement>(null);
  const [last, setLast] = useState(() => (prefersReducedMotion() ? LAST : -1));

  useEffect(() => {
    if (last === LAST) return;
    const node = ref.current;
    if (!node || typeof IntersectionObserver === 'undefined') {
      setLast(LAST);
      return;
    }
    const timers: number[] = [];
    const observer = new IntersectionObserver(
      (entries) => {
        if (!entries.some((entry) => entry.isIntersecting)) return;
        observer.disconnect();
        ROWS.forEach((_, i) => timers.push(window.setTimeout(() => setLast(i), 500 + i * 750)));
      },
      { threshold: 0.4 },
    );
    observer.observe(node);
    return () => {
      observer.disconnect();
      timers.forEach((id) => window.clearTimeout(id));
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- plays once
  }, []);

  const done = last === LAST;
  const status = last < 0 ? t('landing.stage.sending') : done ? t('landing.stage.delivered', { n: ROWS.length }) : t('landing.stage.retrying');
  const statusColour = last < 0 ? undefined : done ? 'hsl(var(--highlight))' : '#FF7A6B';

  return (
    <div ref={ref} className="lp-stage mt-16" data-motion={done ? 'static' : 'running'}>
      <div aria-hidden="true" className="lp-guide-h top-[84px]" />
      <div aria-hidden="true" className="lp-guide-h top-[140px]" />
      <div aria-hidden="true" className="lp-guide-v left-[56px] max-[900px]:left-5" />
      <span aria-hidden="true" className="absolute left-16 top-[68px] z-[2] font-mono text-[10px] leading-none text-[#BDBDBD] max-[900px]:hidden">
        EVT 2Kx9fQ
      </span>
      <div className="relative z-[1] px-5 py-9 min-[901px]:px-14 min-[901px]:py-[60px]">
        <div className="lp-flow mt-6" aria-hidden="true">
          <Node>{t('landing.stage.yourApp')}</Node>
          <Wire mark="✓">{t('landing.stage.stored')}</Wire>
          <Node hl>Railhook</Node>
          <Wire mark={last < 0 ? '…' : done ? '✓' : '✕'} markClassName={last >= 0 && !done ? 'lp-wire-fail' : undefined}>
            {t('landing.stage.attempt', { n: Math.max(1, last + 1) })}
          </Wire>
          <Node>api.acme-shop.com</Node>
        </div>
        <div className="lp-attempts" role="table" aria-label={t('landing.stage.aria')}>
          <div role="row" className="flex justify-between gap-4 border-b border-white/20 px-4 py-3 font-mono text-xs leading-none text-[#CFCFCF]">
            <span role="columnheader">
              <b className="font-medium text-white">order.paid</b> → api.acme-shop.com/webhooks
            </span>
            <span role="columnheader" style={{ color: statusColour }}>
              {status}
            </span>
          </div>
          {ROWS.map((row, i) => (
            <div key={row.at} role="row" className="lp-attempts-row" data-on={i <= last}>
              <span role="cell">{i + 1}</span>
              <span role="cell">{row.at}</span>
              <span role="cell" style={{ color: row.outcome === 'ok' ? 'hsl(var(--highlight))' : '#FF7A6B' }}>
                {row.outcome === 'ok' ? t('landing.stage.ok') : row.outcome}
              </span>
              <span role="cell">
                {row.wait
                  ? t('landing.stage.next', {
                      wait: row.wait.unit === 'minutes'
                        ? t('landing.stage.minutes', { n: row.wait.n })
                        : t('landing.stage.hours', { n: row.wait.n }),
                    })
                  : t('landing.stage.ms', { n: 141 })}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
