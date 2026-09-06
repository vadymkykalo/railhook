import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ExternalLink, Search } from 'lucide-react';
import { ErrorState } from '../../components/EmptyState';
import { Input } from '../../components/ui/input';
import { Skeleton } from '../../components/ui/skeleton';
import { cn } from '../../lib/utils';
import SpecText from './SpecText';
import { CodeBlock } from './primitives';
import { DocsTitle, Route, Section } from './primitives';
import {
  CANONICAL_REFERENCE_URL,
  CANONICAL_SPEC_URL,
  loadApiIndex,
  type ApiIndex,
  type SpecField,
  type SpecOperation,
} from './apiIndex';

/**
 * The API reference, rendered from the spec rather than written by hand.
 *
 * Everything below comes out of `api-index.generated.json`, which is derived
 * from the committed `openapi.yaml` — the same file CI diffs against what
 * springdoc actually serves, and the same file the published Redoc site is
 * built from. Adding an endpoint to the backend puts it here; nobody has to
 * remember to copy it.
 */

function useApiIndex() {
  const [state, setState] = useState<{ index?: ApiIndex; error?: unknown }>({});

  useEffect(() => {
    let live = true;
    loadApiIndex().then(
      (index) => live && setState({ index }),
      (error) => live && setState({ error })
    );
    return () => {
      live = false;
    };
  }, []);

  return state;
}

function FieldTable({ fields, caption }: { fields: SpecField[]; caption: string }) {
  const { t } = useTranslation();
  if (fields.length === 0) return null;
  return (
    <div className="space-y-2">
      <div className="mono-label">{caption}</div>
      <dl className="divide-y divide-rail border-y border-rail">
        {fields.map((field) => (
          <div key={field.name} className="grid gap-1 py-2.5 sm:grid-cols-[minmax(0,16rem)_1fr] sm:gap-4">
            <dt className="min-w-0">
              <code className="break-all font-mono text-[13px] text-foreground">{field.name}</code>
              <span className="ml-2 font-mono text-[11px] text-muted-foreground">{field.type}</span>
              {!field.required && (
                <span className="ml-2 text-[11px] text-muted-foreground">
                  {t('docsPage.paramTable.optional').toLowerCase()}
                </span>
              )}
            </dt>
            <dd className="space-y-1 text-sm text-muted-foreground">
              {field.description && <SpecText text={field.description} className="leading-relaxed" />}
              {field.values && (
                <div className="font-mono text-[11px] text-muted-foreground">{field.values.join(' · ')}</div>
              )}
            </dd>
          </div>
        ))}
      </dl>
    </div>
  );
}

/**
 * The fields of a schema, in one place because a request body and a response body are the
 * same thing pointed in different directions.
 *
 * The response side used to print only the schema's name — "200 EventResponse OK" — while the
 * request side listed every field. So the reference answered what to send and left what comes
 * back as a name to go and look up somewhere else, which for a generated page is the one
 * thing it was supposed to save the reader.
 */
function FieldList({ fields }: { fields: SpecField[] }) {
  const { t } = useTranslation();
  return (
    <dl className="divide-y divide-rail border-y border-rail">
      {fields.map((field) => (
        <div key={field.name} className="grid gap-1 py-2.5 sm:grid-cols-[minmax(0,16rem)_1fr] sm:gap-4">
          <dt className="min-w-0">
            <code className="break-all font-mono text-[13px] text-foreground">{field.name}</code>
            <span className="ml-2 font-mono text-[11px] text-muted-foreground">{field.type}</span>
            {field.required && (
              <span className="ml-2 text-[11px] text-muted-foreground">
                {t('docsPage.paramTable.required').toLowerCase()}
              </span>
            )}
          </dt>
          <dd className="space-y-1 text-sm text-muted-foreground">
            {field.description && <SpecText text={field.description} className="leading-relaxed" />}
            {field.values && (
              <div className="font-mono text-[11px] text-muted-foreground">{field.values.join(' · ')}</div>
            )}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * A Spring `Page` envelope, and the type it actually carries.
 *
 * `PageAlertEventResponse` is eleven fields of framework — totalPages, pageable, sort, first,
 * last, empty — around one that matters. Tabulating all of them buried the item type behind
 * `content: AlertEventResponse[]`; the example above shows the envelope in full, so the table
 * beneath it documents the item.
 */
function unwrapPage(
  type: string | undefined,
  schemas: ApiIndex['schemas'],
): { type: string; paged: boolean } | undefined {
  if (!type) return undefined;
  const base = type.replace(/\[\]$/, '');
  const fields = schemas[base];
  if (!fields) return undefined;
  const content = fields.find((f) => f.name === 'content');
  if (!content || !fields.some((f) => f.name === 'totalElements')) return { type: base, paged: false };
  const inner = content.type.replace(/\[\]$/, '');
  return schemas[inner] ? { type: inner, paged: true } : { type: base, paged: false };
}

function Operation({
  operation,
  schemas,
  examples,
}: {
  operation: SpecOperation;
  schemas: ApiIndex['schemas'];
  examples: ApiIndex['examples'];
}) {
  const { t } = useTranslation();
  const bodyFields = operation.body ? schemas[operation.body.type.replace(/\[\]$/, '')] : undefined;

  return (
    <article id={operation.id} className="scroll-mt-24 space-y-4 border-t border-rail pt-8 first:border-t-0 first:pt-0">
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-[15px] font-medium">{operation.summary || operation.id}</h3>
          {operation.deprecated && (
            <span className="mono-label rounded border border-rail px-1.5 py-0.5">
              {t('docsPage.reference.deprecated')}
            </span>
          )}
        </div>
        <Route method={operation.method} path={operation.path} />
        {operation.description && operation.description !== operation.summary && (
          <SpecText text={operation.description} className="max-w-2xl text-sm leading-relaxed text-muted-foreground" />
        )}
        {operation.auth.length > 0 && (
          <p className="text-xs text-muted-foreground">
            <span className="mono-label mr-1.5">{t('docsPage.reference.auth')}</span>
            <span className="font-mono">{operation.auth.join(' · ')}</span>
          </p>
        )}
      </div>

      <FieldTable
        caption={t('docsPage.reference.parameters')}
        fields={operation.params.map((p) => ({
          name: `${p.name}`,
          type: `${p.type} · ${p.in}`,
          required: p.required,
          description: p.description,
          values: p.values,
        }))}
      />

      {operation.body && (
        <div className="space-y-2">
          <div className="mono-label">
            {t('docsPage.reference.requestBody')} · {operation.body.type} · {operation.body.contentType}
          </div>
          {bodyFields ? (
            <FieldList fields={bodyFields} />
          ) : (
            <p className="font-mono text-[13px] text-muted-foreground">{operation.body.type}</p>
          )}
        </div>
      )}

      <div className="space-y-3">
        <div className="mono-label">{t('docsPage.reference.responses')}</div>
        {operation.responses.map((response) => {
          /* `EventResponse[]` and `EventResponse` are the same shape; a wrapper type the
             generator could not flatten (`map<string, integer>`, a bare `string`) resolves to
             nothing and keeps the one-line treatment it always had. */
          const base = response.type?.replace(/\[\]$/, '');
          const example = base ? examples[base] : undefined;
          const isArray = Boolean(response.type?.endsWith('[]'));
          const carried = unwrapPage(response.type, schemas);
          const fields = carried ? schemas[carried.type] : undefined;
          return (
            <div key={response.status} className="space-y-2">
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 border-t border-rail pt-2.5">
                <span className="font-mono text-[13px] text-foreground">{response.status}</span>
                {response.type && (
                  <span className="font-mono text-[11.5px] text-muted-foreground">{response.type}</span>
                )}
                <span className="text-sm text-muted-foreground">{response.description}</span>
              </div>
              {example !== undefined && (
                <CodeBlock
                  code={JSON.stringify(isArray ? [example] : example, null, 2)}
                  label="json"
                />
              )}
              {fields && (
                <details className="group">
                  <summary className="cursor-pointer list-none py-1 text-[13px] text-muted-foreground hover:text-foreground [&::-webkit-details-marker]:hidden">
                    <span className="group-open:hidden">
                      {t('docsPage.reference.showFields', { type: carried?.type })}
                    </span>
                    <span className="hidden group-open:inline">
                      {t('docsPage.reference.hideFields', { type: carried?.type })}
                    </span>
                  </summary>
                  <FieldList fields={fields} />
                </details>
              )}
            </div>
          );
        })}
      </div>
    </article>
  );
}

export default function ApiReference({
  group,
  onGroupChange,
}: {
  group?: string;
  onGroupChange: (group: string) => void;
}) {
  const { t } = useTranslation();
  const { index, error } = useApiIndex();
  const [query, setQuery] = useState('');

  const activeGroup = useMemo(() => {
    if (!index) return undefined;
    return index.groups.find((g) => g.id === group) ?? index.groups[0];
  }, [index, group]);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!index || needle.length === 0) return undefined;
    return index.groups
      .flatMap((g) => g.operations.map((op) => ({ op, groupName: g.name })))
      .filter(
        ({ op }) =>
          op.path.toLowerCase().includes(needle) ||
          op.summary.toLowerCase().includes(needle) ||
          op.id.toLowerCase().includes(needle) ||
          op.method.toLowerCase() === needle
      )
      .slice(0, 60);
  }, [index, query]);

  if (error) {
    return <ErrorState error={error} fallbackKey="docsPage.reference.loadError" />;
  }

  return (
    <div className="space-y-10 pb-16">
      <DocsTitle title={t('docsPage.reference.title')} lede={t('docsPage.reference.lede')} />

      <div className="flex flex-wrap items-center gap-x-5 gap-y-2 border-y border-rail py-3 text-xs text-muted-foreground">
        <span>
          <span className="mono-label mr-1.5">{t('docsPage.reference.sourceOfTruth')}</span>
          {index ? t('docsPage.reference.operationCount', { n: index.operationCount }) : '—'}
        </span>
        <a
          href={CANONICAL_REFERENCE_URL}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1.5 text-primary hover:underline"
        >
          {t('docsPage.reference.canonicalLink')}
          <ExternalLink className="h-3 w-3" aria-hidden />
        </a>
        <a
          href={CANONICAL_SPEC_URL}
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1.5 text-primary hover:underline"
        >
          {t('docsPage.reference.specLink')}
          <ExternalLink className="h-3 w-3" aria-hidden />
        </a>
      </div>

      <div className="relative">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
        <Input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('docsPage.reference.searchPlaceholder')}
          aria-label={t('docsPage.reference.searchLabel')}
          className="pl-9 font-mono text-[13px]"
        />
      </div>

      {!index && (
        <div className="space-y-3" aria-busy>
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </div>
      )}

      {index && matches && (
        <Section title={t('docsPage.reference.searchResults', { n: matches.length })}>
          {matches.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t('docsPage.reference.noMatches')}</p>
          ) : (
            <ul className="divide-y divide-rail border-y border-rail">
              {matches.map(({ op, groupName }) => (
                <li key={`${op.method}-${op.path}-${op.id}`} className="py-2.5">
                  <Route method={op.method} path={op.path} />
                  <p className="mt-1 text-xs text-muted-foreground">
                    {op.summary} <span className="mono-label ml-1">{groupName}</span>
                  </p>
                </li>
              ))}
            </ul>
          )}
        </Section>
      )}

      {index && !matches && (
        <>
          <nav aria-label={t('docsPage.reference.groupsLabel')} className="flex flex-wrap gap-1.5">
            {index.groups.map((g) => (
              <button
                key={g.id}
                type="button"
                onClick={() => onGroupChange(g.id)}
                aria-current={activeGroup?.id === g.id ? 'true' : undefined}
                className={cn(
                  'rounded-md border px-2.5 py-1 font-mono text-[11px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                  activeGroup?.id === g.id
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-rail text-muted-foreground hover:bg-secondary hover:text-foreground'
                )}
              >
                {g.name}
                <span className="ml-1.5 opacity-60">{g.operations.length}</span>
              </button>
            ))}
          </nav>

          {activeGroup && (
            <section className="space-y-8">
              <div className="space-y-2">
                <h2 className="text-title">{activeGroup.name}</h2>
                {activeGroup.description && (
                  <SpecText
                    text={activeGroup.description}
                    className="max-w-2xl leading-relaxed text-muted-foreground"
                  />
                )}
              </div>
              {activeGroup.operations.map((op) => (
                <Operation
                  key={`${op.method}-${op.path}-${op.id}`}
                  operation={op}
                  schemas={index.schemas}
                  examples={index.examples}
                />
              ))}
            </section>
          )}
        </>
      )}
    </div>
  );
}
