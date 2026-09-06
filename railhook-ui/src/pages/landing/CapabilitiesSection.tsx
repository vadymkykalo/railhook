import { Link } from 'react-router-dom';
import {
  AlertTriangle, ArrowRight, FileJson2, FlaskConical, GitBranch, Radio, Repeat2, Shield,
  TerminalSquare, Workflow,
} from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { panel, Reveal, Section, SectionHeader } from './primitives';
import { cn } from '../../lib/utils';

/**
 * What the platform does to a webhook, for someone deciding whether to buy it.
 *
 * This slot used to be a quickstart: a curl-and-compose block and three
 * localhost ports. That is documentation — it belongs in /docs, and in the
 * Start section further down, which shows three requests and stops. What
 * belongs here is the work the reader would otherwise write themselves, and
 * every one of these is a screen in the product today:
 *
 *   transformations  TransformationController + PayloadTransformService
 *   rules            RuleController, actions ROUTE / TRANSFORM / DROP / TAG
 *   workflows        WorkflowController + the nine node executors
 *   schemas          SchemaController, SchemaValidationPolicy WARN | BLOCK
 *   pii              PiiMaskingController, PiiSanitizer's email/phone/card
 *   alerts           AlertController + AlertNotificationService's four channels
 *   console          TestConsolePage — WebCrypto, the secret stays in the tab
 *   studio           TransformStudioPage + DeliveryDryRunService
 *   tunnels          TunnelController + the CLI's WebSocketTunnelClient
 *
 * The last three were shipped and then never mentioned outside the app, which
 * is the expensive kind of invisible: they are the three a developer can try in
 * their first session.
 */

/**
 * Two groups, because the nine are two different promises.
 *
 * Six are things the platform does to a webhook whether anyone is watching or
 * not. Three are things a developer drives — and those are the three the
 * docblock above notes were shipped and then never mentioned outside the app,
 * which is the expensive kind of invisible. Splitting them is also what stops
 * this section and Security below it reading as one eighteen-card wall: they
 * were the same 3-up grid twice in a row, the one stretch of this page that
 * would look at home on any other product's.
 *
 * They are deliberately not numbered. Numbering would claim an order a payload
 * passes through, and while these six do compose, the page cannot state that
 * sequence honestly — a structural device that encodes something false is worse
 * than no device.
 */
const PIPELINE = [
  { key: 'schemas', icon: FileJson2 },
  { key: 'pii', icon: Shield },
  { key: 'rules', icon: GitBranch },
  { key: 'transformations', icon: Repeat2 },
  { key: 'workflows', icon: Workflow },
  { key: 'alerts', icon: AlertTriangle },
] as const;

const TOOLS = [
  { key: 'console', icon: TerminalSquare },
  { key: 'studio', icon: FlaskConical },
  { key: 'tunnels', icon: Radio },
] as const;

export default function CapabilitiesSection() {
  const { t } = useTranslation();

  return (
    <Section id="capabilities">
      <Reveal>
        <SectionHeader
          eyebrow={t('landing.capabilities.eyebrow')}
          title={t('landing.capabilities.title')}
          body={t('landing.capabilities.body')}
        />
      </Reveal>

      <Reveal>
        <p className="mono-label mt-10">{t('landing.capabilities.pipelineLabel')}</p>
      </Reveal>

      {/* A ruled list, not a card grid: these are six facts about one system,
          and reading them in a column is what makes them read as one system. */}
      <ul className="mt-4 grid border-t border-rail sm:grid-cols-2">
        {PIPELINE.map(({ key, icon: Icon }, i) => (
          <Reveal key={key} delay={i * 40}>
            <li className="flex h-full gap-4 border-b border-rail py-5 pr-6 sm:even:pl-6 sm:even:border-l">
              <Icon className="mt-0.5 h-4 w-4 flex-shrink-0 text-primary" aria-hidden="true" />
              <div className="min-w-0">
                <h3 className="text-[15px] font-semibold text-foreground">
                  {t(`landing.capabilities.${key}Title`)}
                </h3>
                <p className="mt-1.5 text-[15px] leading-relaxed text-muted-foreground">
                  {t(`landing.capabilities.${key}Body`)}
                </p>
              </div>
            </li>
          </Reveal>
        ))}
      </ul>

      <Reveal>
        <div className="mt-10">
          <p className="mono-label">{t('landing.capabilities.toolsLabel')}</p>
          <p className="mt-1.5 max-w-2xl text-sm text-muted-foreground">
            {t('landing.capabilities.toolsBody')}
          </p>
        </div>
      </Reveal>

      {/* The three you drive keep the panel, because a panel you are meant to
          read as an option is the one thing on this page allowed to lift. */}
      <div className="mt-4 grid gap-4 sm:grid-cols-3">
        {TOOLS.map(({ key, icon: Icon }, i) => (
          <Reveal key={key} delay={i * 50} className="h-full">
            <div className={cn('flex h-full flex-col p-5', panel(true))}>
              <Icon className="h-4 w-4 text-primary" aria-hidden="true" />
              <h3 className="mt-3 text-[15px] font-semibold text-foreground">
                {t(`landing.capabilities.${key}Title`)}
              </h3>
              <p className="mt-2 text-[13.5px] leading-relaxed text-muted-foreground">
                {t(`landing.capabilities.${key}Body`)}
              </p>
            </div>
          </Reveal>
        ))}
      </div>

      <div className="mt-8 flex flex-col gap-3 border-t border-rail pt-6 sm:flex-row sm:items-center sm:justify-between">
        <p className="max-w-3xl text-sm text-muted-foreground">{t('landing.capabilities.note')}</p>
        <Link
          to="/docs"
          className="inline-flex shrink-0 items-center gap-1.5 text-sm font-medium text-primary hover:underline"
        >
          {t('landing.capabilities.docsLink')} <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
        </Link>
      </div>
    </Section>
  );
}
