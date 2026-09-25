import { Trans, useTranslation } from 'react-i18next';
import { docsUrl } from '../../lib/docsUrl';
import { cn } from '../../lib/utils';
import { ArrowLink, IN, Node, Wire } from './parts';

// The backend's default outgoing schedule; change both together.
const WAITS = ['—', '1m', '5m', '15m', '1h', '6h', '24h'];
const SINCE = ['0', '1m', '6m', '21m', '1h 21m', '7h 21m', '31h 21m'];

function Stairs() {
  const blocks: Array<{ left: string; top?: string; bottom?: string; width?: string; bg?: string }> = [
    { left: '0', top: '40px' },
    { left: '40px', top: '40px', bg: 'hsl(var(--background))' },
    { left: '0', bottom: '0', width: '320px' },
    { left: '80px', top: '40px', width: '40px' },
    { left: '320px', bottom: '0', width: 'calc(100% - 320px)' },
    { left: '120px', top: '80px', width: '200px' },
  ];
  return (
    <div aria-hidden="true" className="lp-stairs">
      {blocks.map((b, i) => (
        <i key={i} style={{ left: b.left, top: b.top, bottom: b.bottom, width: b.width, background: b.bg }} />
      ))}
    </div>
  );
}

export default function ReliabilitySection() {
  const { t, i18n } = useTranslation();
  const cell = 'whitespace-nowrap border-b border-[#2E2E2E] px-5 py-3.5 text-left';

  return (
    <>
      <Stairs />
      <section aria-labelledby="reliability-title" className="bg-[#111] text-white">
        <div className={cn(IN, 'pb-[100px] pt-[60px]')}>
          <p className="font-mono text-xs uppercase leading-none tracking-[0.06em] text-[#DADADA]">{t('landing.reliability.label')}</p>
          <div className="mt-[18px] flex flex-col items-start gap-[30px] min-[901px]:flex-row min-[901px]:items-end min-[901px]:justify-between">
            <h2 id="reliability-title" className="text-[30px] font-normal leading-[1.15] tracking-[-0.02em] text-white min-[901px]:text-[40px]">
              {t('landing.reliability.title')}
              <br />
              <span className="text-[#9C9C9C]">{t('landing.reliability.titleMuted')}</span>
            </h2>
            <ArrowLink dark href={docsUrl(i18n.language, 'outgoing/retries')} className="flex-none">
              {t('landing.reliability.how')}
            </ArrowLink>
          </div>

          <figure className="mt-[72px] min-[901px]:mt-[110px]">
            <div className="lp-flow" role="img" aria-label={t('landing.reliability.pipeAria')}>
              <Node>POST /api/v1/events</Node>
              <Wire mark="✓">{t('landing.reliability.written')}</Wire>
              <Node>Kafka</Node>
              <Wire mark="✓">{t('landing.reliability.pickedUp')}</Wire>
              <Node hl>{t('landing.reliability.worker')}</Node>
            </div>
            <figcaption className="mt-12 text-center font-mono text-xs uppercase leading-snug tracking-[0.08em] text-[#9C9C9C] min-[901px]:mt-[70px]">
              {t('landing.reliability.caption')}
            </figcaption>
          </figure>

          <div className="mt-[72px] overflow-x-auto border border-[#2E2E2E] min-[901px]:mt-[90px]">
            <div className="flex justify-between gap-4 border-b border-[#2E2E2E] px-5 py-4 font-mono text-xs uppercase leading-none tracking-[0.06em] text-[#DADADA]">
              <span>{t('landing.reliability.scheduleTitle')}</span>
              <span className="text-right">{t('landing.reliability.perEndpoint')}</span>
            </div>
            <table className="w-full border-collapse font-mono text-sm leading-[1.3] text-[#DADADA]">
              <thead>
                <tr>
                  <th scope="col" className={cn(cell, 'font-normal text-[#8A8A8A]')}>{t('landing.reliability.attempt')}</th>
                  {WAITS.map((_, i) => (
                    <th key={i} scope="col" className={cn(cell, 'font-normal text-[#8A8A8A]')}>{i + 1}</th>
                  ))}
                  <th className={cell} aria-hidden="true" />
                </tr>
              </thead>
              <tbody>
                <tr>
                  <th scope="row" className={cn(cell, 'font-sans font-normal text-[#8A8A8A]')}>{t('landing.reliability.waits')}</th>
                  {WAITS.map((w, i) => (
                    <td key={i} className={cell}>{w}</td>
                  ))}
                  <td className={cn(cell, 'text-highlight')}>{t('landing.reliability.failed')}</td>
                </tr>
                <tr>
                  <th scope="row" className={cn(cell, 'font-sans font-normal text-[#8A8A8A]')}>{t('landing.reliability.since')}</th>
                  {SINCE.map((s, i) => (
                    <td key={i} className={cell}>{s}</td>
                  ))}
                  <td className={cell} />
                </tr>
              </tbody>
            </table>
            <p className="px-5 py-4 text-[15px] leading-relaxed text-[#BDBDBD] [&_code]:text-[13.5px] [&_code]:text-white">
              <Trans i18nKey="landing.reliability.note" components={{ c: <code className="font-mono" /> }} />
            </p>
          </div>
        </div>
      </section>
    </>
  );
}
