import { useEffect, useMemo, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Github } from 'lucide-react';
import changelog from 'virtual:changelog';
import { Button } from '../components/ui/button';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { parseChangelog, type Block, type Inline, type ListItem } from '../lib/changelog';
import { Band, PageIntro } from './landing/primitives';
import { REPO_URL } from './landing/plans';

/**
 * The release history, from the repository's CHANGELOG.md as it stood when this build was made.
 *
 * The file is the one source: the page is generated from it at build time rather than written
 * a second time, so a release that is in the changelog is on the site. Notes stay in English —
 * they are written once, for the repository — and the page says so to a reader in another
 * language. Each release has its own anchor, so a link to one version survives new releases.
 */

function InlineContent({ nodes }: { nodes: Inline[] }) {
  return (
    <>
      {nodes.map((node, index) => {
        switch (node.type) {
          case 'text':
            return node.value;
          case 'code':
            return (
              <code key={index} className="rounded bg-muted px-1 py-0.5 font-mono text-[0.85em] text-foreground [overflow-wrap:anywhere]">
                {node.value}
              </code>
            );
          case 'strong':
            return (
              <strong key={index} className="font-semibold text-foreground">
                <InlineContent nodes={node.children} />
              </strong>
            );
          case 'em':
            return (
              <em key={index}>
                <InlineContent nodes={node.children} />
              </em>
            );
          case 'link': {
            const external = /^https?:\/\//.test(node.href);
            return (
              <a
                key={index}
                href={node.href}
                className="font-medium text-primary hover:underline"
                {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
              >
                <InlineContent nodes={node.children} />
              </a>
            );
          }
        }
      })}
    </>
  );
}

function Items({ items }: { items: ListItem[] }) {
  return (
    <ul className="mt-3 list-disc space-y-2 pl-5 marker:text-muted-foreground">
      {items.map((item, index) => (
        <li key={index} className="pl-1">
          <InlineContent nodes={item.content} />
          {item.children.length > 0 && <Items items={item.children} />}
        </li>
      ))}
    </ul>
  );
}

function BlockContent({ block }: { block: Block }): ReactNode {
  switch (block.type) {
    case 'heading':
      return block.level === 3 ? (
        <h3 className="mono-label mt-7 text-primary first:mt-0">{block.text}</h3>
      ) : (
        <h4 className="mt-6 font-semibold text-foreground">{block.text}</h4>
      );
    case 'paragraph':
      return (
        <p className="mt-3 first:mt-0">
          <InlineContent nodes={block.content} />
        </p>
      );
    case 'list':
      return <Items items={block.items} />;
  }
}

/** Scrolls to `#v2.21.0` on arrival; the public layout only scrolls to the top. */
function useScrollToHash() {
  const { hash } = useLocation();
  useEffect(() => {
    if (!hash) return;
    document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView?.();
  }, [hash]);
}

export default function ChangelogPage() {
  const { t, i18n } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.changelog.title', descriptionKey: 'meta.changelog.description', path: '/changelog' });
  useScrollToHash();
  const releases = useMemo(() => parseChangelog(changelog), []);
  const dates = useMemo(
    () => new Intl.DateTimeFormat(i18n.language, { dateStyle: 'long', timeZone: 'UTC' }),
    [i18n.language],
  );
  const english = i18n.language.split('-')[0] === 'en';

  return (
    <>
      <PageIntro eyebrow={t('changelog.eyebrow')} title={t('changelog.title')} lead={t('changelog.lead')}>
        <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
          <Button asChild variant="outline" className="max-sm:w-full">
            <a href={`${REPO_URL}/releases`} target="_blank" rel="noopener noreferrer">
              <Github className="h-4 w-4" aria-hidden="true" />
              {t('changelog.releases')}
            </a>
          </Button>
          {!english && <p className="text-sm text-muted-foreground">{t('changelog.languageNote')}</p>}
        </div>
      </PageIntro>

      <Band>
        <ol>
          {releases.map((release) => (
            <li key={release.id} className="border-t border-rail first:border-t-0">
              <article
                id={release.id}
                aria-labelledby={`${release.id}-title`}
                className="grid scroll-mt-20 gap-4 py-10 md:grid-cols-[11rem_minmax(0,1fr)] md:gap-10"
              >
                <header className="md:sticky md:top-24 md:self-start">
                  <div className="flex items-baseline gap-2">
                    <h2
                      id={`${release.id}-title`}
                      className="font-display text-[1.5rem] font-bold tracking-[-0.02em] text-foreground"
                    >
                      {release.version}
                    </h2>
                    <a
                      href={`#${release.id}`}
                      aria-label={t('changelog.anchor', { version: release.version })}
                      className="font-mono text-sm text-muted-foreground transition-colors hover:text-primary"
                    >
                      #
                    </a>
                  </div>
                  <time dateTime={release.date} className="mt-1 block font-mono text-xs text-muted-foreground">
                    {dates.format(new Date(`${release.date}T00:00:00Z`))}
                  </time>
                </header>
                <div lang="en" className="min-w-0 break-words text-[15px] leading-relaxed text-muted-foreground">
                  {release.blocks.map((block, index) => (
                    <BlockContent key={index} block={block} />
                  ))}
                </div>
              </article>
            </li>
          ))}
        </ol>
      </Band>
    </>
  );
}
