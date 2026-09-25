import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Check, Copy, RefreshCw, ShieldCheck } from 'lucide-react';
import { Button } from '../components/ui/button';
import CaptchaWidget, { isCaptchaConfigured } from '../components/CaptchaWidget';
import JsonBlock from '../components/JsonBlock';
import SyntaxHighlight from '../components/SyntaxHighlight';
import { publicBinApi, type PublicBin, type PublicBinRequest } from '../api/publicBin.api';
import { queryKeys } from '../api/queries';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import { formatRelativeTime } from '../lib/date';
import { publicTesterEnabled } from '../lib/runtimeConfig';
import { cn } from '../lib/utils';
import { Band, WRAP } from './landing/primitives';

/** A URL is made only on request, never on load: crawlers and the prerender load this page too. */
export const STORAGE_KEY = 'railhook.tester.slug';
const POLL_MS = 3000;

function readSlug(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

function writeSlug(slug: string | null) {
  try {
    if (slug) localStorage.setItem(STORAGE_KEY, slug);
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    /* Private mode: the URL simply is not remembered. */
  }
}

function status(error: unknown): number | undefined {
  return (error as { response?: { status?: number } } | null)?.response?.status;
}

function errorCode(error: unknown): string | undefined {
  return (error as { response?: { data?: { error?: string } } } | null)?.response?.data?.error;
}

function createErrorKey(error: unknown): string {
  const code = errorCode(error);
  if (code === 'too_many_active_urls') return 'tester.errors.tooManyActive';
  if (code === 'tester_busy' || status(error) === 503) return 'tester.errors.busy';
  if (code === 'captcha_failed') return 'tester.errors.captcha';
  if (status(error) === 429) return 'tester.errors.tooMany';
  return 'tester.errors.generic';
}

const LIMITS = ['lifetime', 'kept', 'rate', 'masked', 'methods'] as const;

function Limits() {
  const { t } = useTranslation();
  return (
    <div>
      <h2 className="text-[1.5rem] font-normal tracking-[-0.02em] text-foreground">{t('tester.limits.title')}</h2>
      <ul className="mt-4 grid gap-3 text-muted-foreground sm:grid-cols-2">
        {LIMITS.map((key) => (
          <li key={key} className="flex gap-2.5">
            <Check className="mt-1 h-4 w-4 flex-none text-primary" strokeWidth={2.5} aria-hidden="true" />
            <span>{t(`tester.limits.${key}`)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

function CopyButton({ value }: { value: string }) {
  const { t } = useTranslation();
  const { copied, copy } = useCopyToClipboard();
  return (
    <button
      type="button"
      onClick={() => copy(value)}
      className="inline-flex flex-none items-center gap-1.5 border border-rail px-2.5 py-1.5 text-[12.5px] font-medium text-foreground transition-colors hover:border-muted-foreground"
    >
      {copied ? <Check className="h-3.5 w-3.5 text-ok" aria-hidden="true" /> : <Copy className="h-3.5 w-3.5" aria-hidden="true" />}
      <span aria-live="polite">{copied ? t('tester.copied') : t('tester.copy')}</span>
    </button>
  );
}

function CodeLine({ value, prompt = false }: { value: string; prompt?: boolean }) {
  return (
    <div className="surface-ink flex items-center gap-3 overflow-hidden border border-rail py-2.5 pl-4 pr-3">
      <pre
        className={cn(
          'min-w-0 flex-1 overflow-x-auto whitespace-pre py-1 font-mono text-[13px] [scrollbar-width:thin]',
          prompt && "before:select-none before:text-muted-foreground before:content-['$_']",
        )}
      >
        <code>{prompt ? <SyntaxHighlight code={value} language="bash" /> : value}</code>
      </pre>
      <CopyButton value={value} />
    </div>
  );
}

const METHOD = 'inline-flex min-w-[3.5rem] justify-center bg-accent px-1.5 py-0.5 font-mono text-[11px] font-medium text-accent-foreground';

function RequestDetail({ request }: { request: PublicBinRequest }) {
  const { t, i18n } = useTranslation();
  const headers = Object.entries(request.headers);
  return (
    <div className="grid gap-4">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <span className={METHOD}>{request.method}</span>
        <span className="text-muted-foreground">{new Date(request.receivedAt).toLocaleString(i18n.language)}</span>
        {request.sourceIp && <span className="text-muted-foreground">· {t('tester.from', { ip: request.sourceIp })}</span>}
        <span className="text-muted-foreground">· {t('tester.size', { size: request.sizeBytes })}</span>
      </div>
      {request.query && (
        <div>
          <h3 className="mono-label mb-1.5">{t('tester.query')}</h3>
          <code className="block break-all border border-rail bg-muted px-3 py-2 font-mono text-[12.5px]">{request.query}</code>
        </div>
      )}
      <div>
        <h3 className="mono-label mb-1.5">{t('tester.headers')}</h3>
        <dl className="grid grid-cols-[minmax(0,12rem)_1fr] gap-x-4 gap-y-1 border border-rail p-3 font-mono text-[12px]">
          {headers.map(([name, value]) => (
            <div key={name} className="contents">
              <dt className="truncate text-muted-foreground">{name}</dt>
              <dd className="break-all">{value}</dd>
            </div>
          ))}
        </dl>
      </div>
      <div>
        <JsonBlock label={t('tester.body')} value={request.body ?? ''} maxHeight="max-h-96" />
        {request.bodyTruncated && <p className="mt-1.5 text-xs text-muted-foreground">{t('tester.truncated')}</p>}
      </div>
    </div>
  );
}

function BinView({ bin, onNew }: { bin: PublicBin; onNew: () => void }) {
  const { t, i18n } = useTranslation();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = bin.requests.find((r) => r.id === selectedId) ?? bin.requests[0];

  return (
    <div className="grid gap-6">
      <div className="grid gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-sm font-medium text-foreground">{t('tester.yourUrl')}</h2>
          <div className="flex items-center gap-3 text-xs text-muted-foreground">
            <span>{t('tester.expires', { time: new Date(bin.expiresAt).toLocaleString(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }) })}</span>
            <button type="button" onClick={onNew} className="inline-flex items-center gap-1 font-medium link-ink">
              <RefreshCw className="h-3 w-3" aria-hidden="true" />
              {t('tester.newUrl')}
            </button>
          </div>
        </div>
        <CodeLine value={bin.url} />
        <h2 className="mt-2 text-sm font-medium text-foreground">{t('tester.tryIt')}</h2>
        <CodeLine
          prompt
          value={`curl -X POST ${bin.url} -H "Content-Type: application/json" -d '{"type":"order.completed","id":42}'`}
        />
        <p className="flex items-start gap-2 text-xs text-muted-foreground">
          <ShieldCheck className="mt-0.5 h-3.5 w-3.5 flex-none text-primary" aria-hidden="true" />
          {t('tester.masked')}
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,20rem)_1fr]">
        <div className="border border-rail bg-card">
          <div className="flex items-center justify-between border-b border-rail px-4 py-3">
            <h2 className="text-sm font-medium">{t('tester.requests')}</h2>
            <span className="font-mono text-xs text-muted-foreground">{t('tester.received', { count: bin.requestCount })}</span>
          </div>
          {bin.requests.length === 0 ? (
            <p className="flex items-center gap-2 px-4 py-6 text-sm text-muted-foreground">
              <span className="h-2 w-2 animate-pulse rounded-full bg-primary motion-reduce:animate-none" aria-hidden="true" />
              {t('tester.waiting')}
            </p>
          ) : (
            <ul aria-label={t('tester.requests')} className="max-h-[32rem] overflow-y-auto">
              {bin.requests.map((r) => (
                <li key={r.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(r.id)}
                    aria-pressed={selected?.id === r.id}
                    className={cn(
                      'flex w-full items-center gap-2.5 border-b border-rail px-4 py-2.5 text-left text-sm last:border-b-0 hover:bg-muted',
                      selected?.id === r.id && 'bg-secondary',
                    )}
                  >
                    <span className={METHOD}>{r.method}</span>
                    <span className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground">
                      {r.contentType ?? '—'}
                    </span>
                    <span className="whitespace-nowrap text-xs text-muted-foreground">{formatRelativeTime(r.receivedAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="min-w-0 border border-rail bg-card p-4">
          {selected ? <RequestDetail request={selected} /> : (
            <p className="text-sm text-muted-foreground">{t('tester.select')}</p>
          )}
        </div>
      </div>
    </div>
  );
}

export default function TesterPage() {
  const { t } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.tester.title', descriptionKey: 'meta.tester.description', path: '/tester' });
  const enabled = publicTesterEnabled();
  const [slug, setSlug] = useState<string | null>(readSlug);
  const [captchaToken, setCaptchaToken] = useState('');

  const bin = useQuery({
    queryKey: queryKeys.publicBin(slug ?? ''),
    queryFn: () => publicBinApi.get(slug!),
    enabled: enabled && !!slug,
    refetchInterval: POLL_MS,
    refetchIntervalInBackground: false,
    retry: false,
  });

  // Expired or never existed: forget it and offer a new one.
  useEffect(() => {
    if (bin.isError && status(bin.error) === 404) {
      writeSlug(null);
      setSlug(null);
    }
  }, [bin.isError, bin.error]);

  const create = useMutation({
    mutationFn: () => publicBinApi.create(captchaToken || undefined),
    onSuccess: (created) => {
      writeSlug(created.slug);
      setSlug(created.slug);
    },
    // A challenge answer is single-use: a failed attempt needs a fresh one.
    onError: () => setCaptchaToken(''),
  });

  /* Back to the form: a new URL needs a fresh challenge answer. */
  const startOver = () => {
    writeSlug(null);
    setSlug(null);
    create.reset();
  };

  const createError = create.isError ? t(createErrorKey(create.error)) : null;
  const needsChallenge = isCaptchaConfigured() && !captchaToken;

  return (
    <>
      <section className="pb-2 pt-14 sm:pt-20">
        <div className={WRAP}>
          <p className="mono-label mb-3">{t('tester.eyebrow')}</p>
          <h1 className="max-w-3xl text-[2.375rem] font-normal leading-[1.16] tracking-[-0.03em] text-foreground [text-wrap:balance] sm:text-[3.5rem]">
            {t('tester.title')}
          </h1>
          <p className="mt-4 max-w-2xl text-[1.1rem] text-muted-foreground">{t('tester.lead')}</p>
        </div>
      </section>

      <Band labelledBy="tester-tool">
        <h2 id="tester-tool" className="sr-only">{t('tester.title')}</h2>
        {!enabled ? (
          <p className="border border-dashed border-rail p-6 text-muted-foreground sm:p-8">{t('tester.disabled')}</p>
        ) : slug && bin.data ? (
          <BinView bin={bin.data} onNew={startOver} />
        ) : slug && bin.isLoading ? (
          <p className="text-sm text-muted-foreground">{t('tester.loading')}</p>
        ) : (
          <div className="flex flex-col items-start gap-3 border border-dashed border-rail p-6 sm:p-8">
            <p className="max-w-xl text-muted-foreground">{t('tester.createHint')}</p>
            {/* Keyed on the failures so a failed attempt renders a fresh widget and a fresh answer. */}
            <CaptchaWidget key={create.failureCount} onToken={setCaptchaToken} />
            <Button onClick={() => create.mutate()} disabled={create.isPending || needsChallenge}>
              {create.isPending ? t('tester.creating') : t('tester.create')}
            </Button>
            {createError && <p role="alert" className="text-sm text-halt">{createError}</p>}
          </div>
        )}
        {enabled && (
          <div className="mt-12">
            <Limits />
          </div>
        )}
      </Band>

      <Band muted labelledBy="tester-cta">
        <div className="flex flex-col items-start justify-between gap-5 md:flex-row md:items-center">
          <div className="max-w-2xl">
            <h2 id="tester-cta" className="text-[1.75rem] font-normal leading-[1.16] tracking-[-0.02em] sm:text-[2rem] text-foreground">
              {t('tester.cta.title')}
            </h2>
            <p className="mt-2 text-muted-foreground">{t('tester.cta.body')}</p>
          </div>
          <div className="flex flex-wrap gap-2.5">
            <Button asChild>
              <Link to="/register">{t('tester.cta.button')}</Link>
            </Button>
            <Button asChild variant="outline">
              <a href="/docs/start/receive-first-webhook/">{t('tester.cta.docs')}</a>
            </Button>
          </div>
        </div>
      </Band>
    </>
  );
}
