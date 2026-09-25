import type { ReactNode } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { docsUrl } from '../../lib/docsUrl';
import { SectionLabel } from './primitives';
import { ArrowLink, IN, LabelBand, RICH, Rule } from './parts';

export const INSTALL_COMMAND = 'curl -fsSL https://railhook.io/install.sh | bash -s -- \\\n  --domain hooks.example.com --email ops@example.com';

export default function SelfHostSection() {
  const { t, i18n } = useTranslation();
  const comment = 'text-[#7C7C7C]';
  const spec: Array<[string, ReactNode]> = [
    [t('landing.selfHost.deployLabel'), t('landing.selfHost.deploy')],
    [t('landing.selfHost.needsLabel'), t('landing.selfHost.needs')],
    [t('landing.selfHost.httpsLabel'), <Trans key="https" i18nKey="landing.selfHost.https" components={RICH} />],
    [t('landing.selfHost.opsLabel'), t('landing.selfHost.ops')],
  ];

  return (
    <>
      <Rule />
      <LabelBand id="self-host">{t('landing.selfHost.label')}</LabelBand>
      <Rule />
      <section aria-labelledby="self-host-title" className="lp-halftone py-10 min-[1001px]:py-[110px]">
        <div className={IN}>
          <div className="relative z-[1] grid grid-cols-1 bg-white text-black min-[1001px]:grid-cols-2">
            {/* Code, not prose: the landing's word-count test skips <pre>. */}
            <pre
              aria-label={t('landing.selfHost.termAria')}
              data-testid="install-command"
              className="overflow-x-auto whitespace-pre bg-[#0E0E0E] px-6 py-7 font-mono text-[13.5px] leading-[1.9] text-[#E5E5E5] sm:px-8"
            >
              <code>
                <span className={comment}>{t('landing.selfHost.termComment').split('\n').map((line) => `# ${line}`).join('\n')}</span>
                {'\n'}
                {INSTALL_COMMAND}
                {'\n\n'}
                <span className={comment}># {t('landing.selfHost.termDayTwo')}</span>
                {'\n./railhook status\n./railhook upgrade    '}
                <span className={comment}># {t('landing.selfHost.termBackup')}</span>
                {'\n./railhook backup'}
              </code>
            </pre>
            <div className="flex flex-col justify-center px-6 py-9 min-[1001px]:px-16 min-[1001px]:py-[60px]">
              <SectionLabel className="gap-2 text-[#333] dark:text-[#333] [&>span]:bg-black">{t('landing.selfHost.tag')}</SectionLabel>
              <h2 id="self-host-title" className="my-4 text-[30px] font-normal leading-[1.16] tracking-[-0.02em] text-black md:text-[36px]">
                <Trans i18nKey="landing.selfHost.title" components={RICH} />
              </h2>
              <p className="mb-[18px] text-[17px] text-[#333]">{t('landing.selfHost.body')}</p>
              <dl className="mb-[22px] border-t border-[#E5E7EB]">
                {spec.map(([label, value]) => (
                  <div key={label} className="grid grid-cols-[110px_1fr] border-b border-[#E5E7EB] py-2.5 text-[15px] text-[#333]">
                    <dt className="font-mono text-[11.5px] uppercase leading-[1.9] tracking-[0.06em] text-[#777]">{label}</dt>
                    <dd>{value}</dd>
                  </div>
                ))}
              </dl>
              <ArrowLink href={docsUrl(i18n.language, 'self-hosting/overview')} className="self-start !text-black">
                {t('landing.selfHost.guide')}
              </ArrowLink>
            </div>
          </div>
        </div>
      </section>
    </>
  );
}
