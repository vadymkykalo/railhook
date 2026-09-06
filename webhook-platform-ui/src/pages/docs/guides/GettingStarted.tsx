import { useTranslation } from 'react-i18next';
import {
  CodeBlock,
  CodeSample,
  DefinitionList,
  DocsArticle,
  DocsTitle,
  Note,
  Route,
  Section,
  SubSection,
} from '../primitives';
import type { SampleLanguage } from '../primitives';
import { authSamples, quickstartSamples } from '../samples';

/**
 * Step 1 used to be `POST /api/v1/auth/login` against a host the reader did not
 * have, so the first step was one nobody could run. `runInstance` is that
 * missing step, and it is the installer rather than a hand-assembled Compose
 * invocation: an integrator reading this wants an instance to talk to, not a
 * deployment decision.
 */
const runInstance = `curl -fsSL https://raw.githubusercontent.com/vadymkykalo/railhook/main/install.sh | bash

# Everything is served on one port. Health, once it is up:
curl -f http://localhost/actuator/health/liveness`;

/**
 * One published port, and the two that are not.
 *
 * This used to list three bindings, from when the dashboard, the API and the actuator each
 * had their own. Only nginx is bound to the host now — it serves the dashboard and proxies
 * every API path — so the other two are here to say where they went, not how to reach them.
 */
const PORTS = [
  { port: '80', envVar: 'RAILHOOK_PORT', key: 'docsPage.gettingStarted.portWeb' },
  { port: '8080', envVar: null, key: 'docsPage.gettingStarted.portApi' },
  { port: '8082', envVar: null, key: 'docsPage.gettingStarted.portActuator' },
];

function Step({
  number,
  title,
  description,
  method,
  path,
  children,
}: {
  number: number;
  title: string;
  description: string;
  method: string;
  path: string;
  children: React.ReactNode;
}) {
  return (
    <li className="grid gap-3 border-t border-rail pt-6 first:border-t-0 first:pt-0">
      <div className="flex items-baseline gap-3">
        <span className="mono-label">{String(number).padStart(2, '0')}</span>
        <h2 className="text-title">{title}</h2>
      </div>
      <p className="max-w-2xl text-muted-foreground">{description}</p>
      <Route method={method} path={path} />
      {children}
    </li>
  );
}

export default function GettingStarted({
  language,
  onLanguageChange,
}: {
  language: SampleLanguage;
  onLanguageChange: (language: SampleLanguage) => void;
}) {
  const { t } = useTranslation();

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.gettingStarted.title')} lede={t('docsPage.gettingStarted.subtitle')} />

      <Section title={t('docsPage.gettingStarted.runTitle')} description={t('docsPage.gettingStarted.runDesc')}>
        <CodeBlock code={runInstance} label="bash" wrap />

        <SubSection title={t('docsPage.gettingStarted.portsTitle')}>
          <DefinitionList
            items={PORTS.map((p) => ({
              term: p.port,
              definition: p.envVar ? `${t(p.key)} \`${p.envVar}\`` : t(p.key),
            }))}
          />
        </SubSection>

        <Note label={t('docsPage.gettingStarted.runBaseUrlLabel')}>{t('docsPage.gettingStarted.runBaseUrlDesc')}</Note>
        <Note label={t('docsPage.gettingStarted.runSecretsLabel')}>{t('docsPage.gettingStarted.runSecretsDesc')}</Note>
      </Section>

      <ol className="space-y-6">
        <Step
          number={1}
          title={t('docsPage.gettingStarted.step1')}
          description={t('docsPage.gettingStarted.step1Desc')}
          method="POST"
          path="/api/v1/auth/login"
        >
          <CodeSample samples={authSamples.login} language={language} onLanguageChange={onLanguageChange} />
        </Step>

        <Step
          number={2}
          title={t('docsPage.gettingStarted.step2')}
          description={t('docsPage.gettingStarted.step2Desc')}
          method="POST"
          path="/api/v1/projects"
        >
          <CodeBlock code={quickstartSamples.project} label="bash" />
        </Step>

        <Step
          number={3}
          title={t('docsPage.gettingStarted.step3')}
          description={t('docsPage.gettingStarted.step3Desc')}
          method="POST"
          path="/api/v1/projects/{projectId}/api-keys"
        >
          <CodeBlock code={quickstartSamples.apiKey} label="bash" />
        </Step>

        <Step
          number={4}
          title={t('docsPage.gettingStarted.step4')}
          description={t('docsPage.gettingStarted.step4Desc')}
          method="POST"
          path="/api/v1/projects/{projectId}/endpoints"
        >
          <CodeBlock code={quickstartSamples.endpoint} label="bash" />
          <Note label={t('docsPage.gettingStarted.verifyLabel')}>{t('docsPage.gettingStarted.verifyDesc')}</Note>
        </Step>

        <Step
          number={5}
          title={t('docsPage.gettingStarted.step5')}
          description={t('docsPage.gettingStarted.step5Desc')}
          method="POST"
          path="/api/v1/projects/{projectId}/subscriptions"
        >
          <CodeBlock code={quickstartSamples.subscription} label="bash" />
        </Step>

        <Step
          number={6}
          title={t('docsPage.gettingStarted.step6')}
          description={t('docsPage.gettingStarted.step6Desc')}
          method="POST"
          path="/api/v1/events"
        >
          <CodeBlock code={quickstartSamples.event} label="bash" />
        </Step>
      </ol>

      <Note label={t('docsPage.gettingStarted.nextLabel')}>{t('docsPage.gettingStarted.nextDesc')}</Note>
    </DocsArticle>
  );
}
