import type { ReactNode } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { docsUrl } from '../../lib/docsUrl';
import { cn } from '../../lib/utils';
import { SectionLabel } from './primitives';
import { ArrowLink, IN, LabelBand, RICH, Rule } from './parts';

const OK = 'text-[#16A34A]';
const NO = 'text-[#DC2626]';

type Cell = { ok: boolean; text: string };

export default function ProductSection() {
  const { t, i18n } = useTranslation();
  const refused = t('landing.product.out.refused');
  const ok = (text: string): Cell => ({ ok: true, text });
  const no = (text: string): Cell => ({ ok: false, text });

  const outgoing: Array<[ReactNode, Cell, Cell, Cell]> = [
    [
      <>
        <span className="mr-2 bg-muted px-[7px] py-[5px] font-mono text-xs leading-none text-[#666]">{t('landing.product.out.portalPill')}</span>
        hooks.northwind.io
      </>,
      ok('200'), ok('200'), ok('204'),
    ],
    ['api.acme-shop.com', ok('200'), no('503'), ok('200')],
    ['erp.globex.com', no(refused), no(refused), ok('200')],
    ['billing.initech.dev', ok('200'), ok('200'), ok('200')],
    ['crm.umbrella.co', ok('202'), no('429'), ok('202')],
    ['hooks.stark.io', ok('200'), ok('200'), no('500')],
    ['notify.wayne.app', ok('200'), ok('200'), ok('200')],
  ];

  const verified = ok(t('landing.product.in.verified'));
  const incoming: Array<[string, Cell, string, Cell]> = [
    ['Stripe', verified, 'billing.internal/stripe', ok('200')],
    ['GitHub', verified, 'ci.internal/github', ok('200')],
    ['Shopify', verified, 'orders.internal/shopify', no(t('landing.product.in.retryIn'))],
    [t('landing.product.in.unknown'), no(t('landing.product.in.invalid')), '—', no(t('landing.product.in.rejected'))],
    ['Twilio', verified, 'sms.internal/twilio', ok('200')],
    ['SendGrid', verified, 'mail.internal/events', ok('202')],
    ['Stripe', verified, 'billing.internal/stripe', ok('200')],
  ];

  return (
    <>
      <LabelBand id="product" soft>
        {t('landing.product.label')}
      </LabelBand>
      <Rule />
      <section aria-labelledby="product-title" className="bg-muted">
        <div className="lp-guides">
          <div className="lp-sel">
            <i />
            <i />
            <i />
            <i />
            <h2
              id="product-title"
              className="text-[28px] font-normal leading-[1.16] tracking-[-0.02em] text-foreground md:text-[38px]"
            >
              <Trans i18nKey="landing.product.title" components={RICH} />
            </h2>
          </div>
        </div>

        <ProductBlock
          className="pt-5"
          tag={t('landing.product.out.tag')}
          title={t('landing.product.out.title')}
          lead={t('landing.product.out.lead')}
          explore={{ href: docsUrl(i18n.language, 'outgoing/endpoints-subscriptions'), label: t('landing.product.out.explore') }}
          feats={[
            { icon: <RetryIcon />, title: t('landing.product.out.retriesTitle'), body: t('landing.product.out.retriesBody') },
            { icon: <LockIcon />, title: t('landing.product.out.signaturesTitle'), body: t('landing.product.out.signaturesBody') },
            { icon: <WindowIcon />, title: t('landing.product.out.portalTitle'), body: t('landing.product.out.portalBody') },
          ]}
          panel="light"
          table={
            <table>
              <thead>
                <tr>
                  <th>{t('landing.product.out.endpoint')}</th>
                  <th>order.paid</th>
                  <th>order.refunded</th>
                  <th>user.created</th>
                </tr>
              </thead>
              <tbody>
                {outgoing.map(([endpoint, ...cells], i) => (
                  <tr key={i}>
                    <td>{endpoint}</td>
                    {cells.map((cell, j) => (
                      <StatusCell key={j} cell={cell} />
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          }
        />

        <ProductBlock
          className="pt-0"
          tag={t('landing.product.in.tag')}
          title={t('landing.product.in.title')}
          lead={t('landing.product.in.lead')}
          explore={{ href: docsUrl(i18n.language, 'incoming/sources'), label: t('landing.product.in.explore') }}
          feats={[
            { icon: <ShieldIcon />, title: t('landing.product.in.verifyTitle'), body: t('landing.product.in.verifyBody') },
            { icon: <ArrowIcon />, title: t('landing.product.in.forwardTitle'), body: t('landing.product.in.forwardBody') },
            { icon: <ReplayIcon />, title: t('landing.product.in.replayTitle'), body: t('landing.product.in.replayBody') },
          ]}
          panel="dark"
          table={
            <table>
              <thead>
                <tr>
                  <th>{t('landing.product.in.source')}</th>
                  <th>{t('landing.product.in.signature')}</th>
                  <th>{t('landing.product.in.forwardedTo')}</th>
                  <th>{t('landing.product.in.result')}</th>
                </tr>
              </thead>
              <tbody>
                {incoming.map(([source, signature, target, result], i) => (
                  <tr key={i}>
                    <td>{source}</td>
                    <StatusCell cell={signature} />
                    <td className="font-mono text-[13.5px]">{target}</td>
                    <StatusCell cell={result} />
                  </tr>
                ))}
              </tbody>
            </table>
          }
        />
      </section>
    </>
  );
}

function StatusCell({ cell }: { cell: Cell }) {
  return <td className={cell.ok ? OK : NO}>{cell.ok ? '✓' : '✕'} {cell.text}</td>;
}

function ProductBlock({
  className,
  tag,
  title,
  lead,
  explore,
  feats,
  panel,
  table,
}: {
  className?: string;
  tag: string;
  title: string;
  lead: string;
  explore: { href: string; label: string };
  feats: Array<{ icon: ReactNode; title: string; body: string }>;
  panel: 'light' | 'dark';
  table: ReactNode;
}) {
  return (
    <div className={cn(IN, 'pb-[110px]', className)}>
      <div className="mb-[52px] flex flex-col items-start gap-10 min-[1101px]:flex-row min-[1101px]:items-end min-[1101px]:justify-between">
        <div>
          <SectionLabel className="mb-3.5 gap-2">{tag}</SectionLabel>
          <h3 className="text-[26px] font-normal leading-[1.1] tracking-[-0.02em] text-foreground md:text-[30px]">
            <span className="mark-hl">{title}</span>
          </h3>
          <p className="mt-3.5 max-w-[610px] text-lg text-[#333] dark:text-muted-foreground">{lead}</p>
        </div>
        <ArrowLink href={explore.href} className="flex-none">
          {explore.label}
        </ArrowLink>
      </div>
      <div className="grid grid-cols-1 border border-rail bg-background min-[1101px]:grid-cols-[382px_minmax(0,1fr)]">
        <ul>
          {feats.map((feat) => (
            <li key={feat.title} className="border-b border-rail px-6 py-7 last:border-b-0 sm:px-10 sm:py-8 max-[1100px]:last:border-b">
              <p className="mb-3.5 flex items-center gap-2 font-mono text-[11.5px] uppercase leading-none tracking-[0.06em] text-[#333] dark:text-muted-foreground">
                {feat.icon}
                {feat.title}
              </p>
              <p className="text-[16.5px] leading-[1.45] text-[#222] dark:text-foreground">{feat.body}</p>
            </li>
          ))}
        </ul>
        <div className={cn('lp-panel', panel === 'light' ? 'lp-panel--light' : 'lp-panel--dark')}>{table}</div>
      </div>
    </div>
  );
}

function Icon({ children }: { children: ReactNode }) {
  return (
    <svg aria-hidden="true" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4 flex-none text-foreground">
      {children}
    </svg>
  );
}
const RetryIcon = () => <Icon><path d="M13 8a5 5 0 1 1-1.5-3.6M13 2v3h-3" /></Icon>;
const LockIcon = () => <Icon><rect x="3" y="7" width="10" height="7" /><path d="M5 7V5a3 3 0 0 1 6 0v2" /></Icon>;
const WindowIcon = () => <Icon><rect x="2" y="3" width="12" height="10" /><path d="M2 6h12" /></Icon>;
const ShieldIcon = () => <Icon><path d="M8 1.5 13.5 4v4c0 3-2.4 5.4-5.5 6.5C4.9 13.4 2.5 11 2.5 8V4z" /><path d="m5.5 8 1.8 1.8L10.8 6" /></Icon>;
const ArrowIcon = () => <Icon><path d="M2 8h11M9 4l4 4-4 4" /></Icon>;
const ReplayIcon = () => <Icon><path d="M3 8a5 5 0 1 0 1.5-3.6M3 2v3h3" /></Icon>;
