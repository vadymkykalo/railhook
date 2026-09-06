import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ExternalLink } from 'lucide-react';
import { CodeBlock, DocsArticle, DocsTitle, Note, Section } from '../primitives';
import { cn } from '../../../lib/utils';
import { sdkSamples } from '../samples';

type SdkId = 'node' | 'python' | 'php';

const SDKS: Array<{ id: SdkId; name: string; pkg: string; url: string; install: string; label: string }> = [
  {
    id: 'node',
    name: 'Node.js / TypeScript',
    pkg: '@railhook/node',
    url: 'https://www.npmjs.com/package/@railhook/node',
    install: 'npm install @railhook/node',
    label: 'typescript',
  },
  {
    id: 'python',
    name: 'Python',
    pkg: 'webhook-platform',
    url: 'https://pypi.org/project/webhook-platform/',
    install: 'pip install railhook',
    label: 'python',
  },
  {
    id: 'php',
    name: 'PHP',
    pkg: 'railhook/php',
    url: 'https://packagist.org/packages/railhook/php',
    install: 'composer require railhook/php',
    label: 'php',
  },
];

export default function Sdks() {
  const { t } = useTranslation();
  const [active, setActive] = useState<SdkId>('node');
  const sdk = SDKS.find((s) => s.id === active) ?? SDKS[0];

  return (
    <DocsArticle>
      <DocsTitle title={t('docsPage.sdks.title')} lede={t('docsPage.sdks.subtitle')} />

      <Section title={t('docsPage.sdks.packages')}>
        <ul className="grid gap-3 sm:grid-cols-3">
          {SDKS.map((entry) => (
            <li key={entry.id}>
              <a
                href={entry.url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex h-full flex-col justify-between gap-2 rounded-lg border border-rail bg-card p-4 transition-colors hover:border-primary/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <span className="text-sm font-medium">{entry.name}</span>
                <span className="inline-flex items-center gap-1.5 break-all font-mono text-[11px] text-muted-foreground">
                  {entry.pkg}
                  <ExternalLink className="h-3 w-3 flex-shrink-0" aria-hidden />
                </span>
              </a>
            </li>
          ))}
        </ul>
      </Section>

      <Section title={t('docsPage.sdks.quickStart')}>
        <div className="flex flex-wrap gap-1">
          {SDKS.map((entry) => (
            <button
              key={entry.id}
              type="button"
              onClick={() => setActive(entry.id)}
              aria-pressed={active === entry.id}
              className={cn(
                'rounded-md px-2.5 py-1 font-mono text-[11px] uppercase tracking-[0.06em] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                active === entry.id
                  ? 'bg-primary text-primary-foreground'
                  : 'text-muted-foreground hover:bg-secondary hover:text-foreground'
              )}
            >
              {entry.name}
            </button>
          ))}
        </div>
        <CodeBlock code={sdk.install} label="bash" />
        <CodeBlock code={sdkSamples[sdk.id]} label={sdk.label} />
      </Section>

      <Note label={t('docsPage.sdks.moreLabel')}>{t('docsPage.sdks.moreDesc')}</Note>
    </DocsArticle>
  );
}
