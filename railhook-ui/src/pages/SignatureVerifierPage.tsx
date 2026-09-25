import { useEffect, useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { AlertTriangle, Check, CheckCircle2, Clock, Copy, ShieldCheck, XCircle } from 'lucide-react';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Label } from '../components/ui/label';
import { Textarea } from '../components/ui/textarea';
import { useCopyToClipboard } from '../hooks/useCopyToClipboard';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { useJsonLd } from '../hooks/useJsonLd';
import { siteUrl } from '../lib/siteUrl';
import { cn } from '../lib/utils';
import {
  exampleHeaders,
  PROVIDERS,
  schemeOf,
  verifySignature,
  type Provider,
  type TimestampCheck,
  type VerifyResult,
} from '../lib/webhookSignature';
import { Band, PageIntro, SectionHeading } from './landing/primitives';

/**
 * A free webhook signature verifier: paste a body, a secret and the signature header, and see
 * whether they match — and what the signature should have been when they do not.
 *
 * Like the tester, it is a first use of Railhook before an account, and something people search
 * for. Unlike the tester it needs no server: the HMAC runs in the browser, which is what makes it
 * reasonable to paste a real secret into it, so the page says that before anything else.
 */
const PATH = '/tools/webhook-signature';

const EXAMPLE_PAYLOAD = '{"id":"evt_1Q2w3E4r","type":"invoice.paid","data":{"amount":4200,"currency":"eur"}}';
const EXAMPLE_SECRETS: Record<Provider, string> = {
  standard: 'whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw',
  stripe: 'whsec_test_5b7c1a0e9d2f4c8b',
  github: 'gh-webhook-secret',
  shopify: 'shpss_example_app_secret',
  slack: '8f742231b10e8888abcd99yyyzzz85a5',
  railhook: 'rh_endpoint_secret_example',
};

type HeaderValues = Record<Provider, Record<string, string>>;

const EMPTY_HEADERS = Object.fromEntries(PROVIDERS.map((p) => [p.id, {}])) as HeaderValues;

/** "5 minutes ago", "in 2 hours": the largest unit that keeps the number readable. */
function relativeTime(ageSeconds: number, language: string): string {
  const format = new Intl.RelativeTimeFormat(language, { numeric: 'auto' });
  const abs = Math.abs(ageSeconds);
  if (abs < 60) return format.format(-ageSeconds, 'second');
  if (abs < 3600) return format.format(-Math.round(ageSeconds / 60), 'minute');
  if (abs < 86_400) return format.format(-Math.round(ageSeconds / 3600), 'hour');
  return format.format(-Math.round(ageSeconds / 86_400), 'day');
}

function ProviderPicker({ value, onChange }: { value: Provider; onChange: (provider: Provider) => void }) {
  const { t } = useTranslation();
  const name = useId();
  return (
    <fieldset>
      <legend className="mb-2.5 text-sm font-medium text-foreground">{t('webhookSignature.provider')}</legend>
      <div className="flex flex-wrap gap-2">
        {PROVIDERS.map((provider) => (
          <label key={provider.id} className="cursor-pointer">
            <input
              type="radio"
              name={name}
              value={provider.id}
              checked={value === provider.id}
              onChange={() => onChange(provider.id)}
              className="peer sr-only"
            />
            <span
              className={cn(
                'inline-flex min-h-10 items-center border px-3.5 text-sm font-medium transition-colors',
                'peer-focus-visible:ring-2 peer-focus-visible:ring-ring peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-background',
                value === provider.id
                  ? 'border-primary bg-accent text-accent-foreground'
                  : 'border-rail bg-card text-muted-foreground hover:border-muted-foreground hover:text-foreground',
              )}
            >
              {t(`webhookSignature.providers.${provider.id}`)}
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

function ExpectedSignature({ value }: { value: string }) {
  const { t } = useTranslation();
  const { copied, copy } = useCopyToClipboard();
  return (
    <div className="mt-5">
      <p className="mono-label mb-1.5">{t('webhookSignature.result.expected')}</p>
      <div className="flex items-start gap-2 border border-rail bg-muted p-2.5">
        <code className="min-w-0 flex-1 break-all font-mono text-[12.5px] text-foreground">{value}</code>
        <button
          type="button"
          onClick={() => copy(value)}
          aria-label={copied ? t('tester.copied') : t('tester.copy')}
          className="grid h-8 w-8 flex-none place-items-center text-muted-foreground transition-colors hover:bg-background hover:text-foreground"
        >
          {copied ? <Check className="h-3.5 w-3.5 text-ok" aria-hidden="true" /> : <Copy className="h-3.5 w-3.5" aria-hidden="true" />}
        </button>
      </div>
    </div>
  );
}

function TimestampNote({ check }: { check: TimestampCheck }) {
  const { t, i18n } = useTranslation();
  const params = { when: relativeTime(check.ageSeconds, i18n.language), minutes: check.toleranceSeconds / 60 };
  return (
    <p className={cn('mt-4 flex items-start gap-2 text-sm', check.withinTolerance ? 'text-muted-foreground' : 'text-foreground')}>
      <Clock
        className={cn('mt-0.5 h-4 w-4 flex-none', check.withinTolerance ? 'text-muted-foreground' : 'text-retry')}
        aria-hidden="true"
      />
      <span>
        {check.withinTolerance
          ? t('webhookSignature.result.timestampOk', params)
          : t('webhookSignature.result.timestampOutside', params)}
      </span>
    </p>
  );
}

function Result({ result }: { result: VerifyResult }) {
  const { t } = useTranslation();
  const heading = 'text-[1.2rem] font-medium tracking-[-0.01em]';

  if (result.status === 'incomplete') {
    return <p className="text-muted-foreground">{t('webhookSignature.result.incomplete')}</p>;
  }
  if (result.status === 'malformed') {
    return (
      <div>
        <p className={cn(heading, 'flex items-center gap-2 text-foreground')}>
          <AlertTriangle className="h-5 w-5 flex-none text-retry" aria-hidden="true" />
          {t('webhookSignature.result.malformed')}
        </p>
        <p className="mt-2 text-muted-foreground">
          {t(`webhookSignature.result.reasons.${result.reason}`, { header: result.header ?? '' })}
        </p>
      </div>
    );
  }
  const valid = result.status === 'valid';
  return (
    <div>
      <p className={cn(heading, 'flex items-center gap-2', valid ? 'text-ok' : 'text-halt')}>
        {valid
          ? <CheckCircle2 className="h-5 w-5 flex-none" aria-hidden="true" />
          : <XCircle className="h-5 w-5 flex-none" aria-hidden="true" />}
        {valid ? t('webhookSignature.result.valid') : t('webhookSignature.result.invalid')}
      </p>
      <p className="mt-2 text-muted-foreground">
        {valid ? t('webhookSignature.result.validBody') : t('webhookSignature.result.invalidBody')}
      </p>
      {result.timestamp && <TimestampNote check={result.timestamp} />}
      <ExpectedSignature value={result.expected} />
    </div>
  );
}

export default function SignatureVerifierPage() {
  const { t } = useTranslation();
  useDocumentMeta({
    titleKey: 'meta.signatureVerifier.title',
    descriptionKey: 'meta.signatureVerifier.description',
    path: PATH,
  });
  useJsonLd('signature-verifier', {
    '@context': 'https://schema.org',
    '@type': 'WebApplication',
    name: t('webhookSignature.title'),
    description: t('meta.signatureVerifier.description'),
    url: `${siteUrl()}${PATH}`,
    applicationCategory: 'DeveloperApplication',
    operatingSystem: 'Any',
    browserRequirements: 'Requires JavaScript',
    isAccessibleForFree: true,
    offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' },
    publisher: { '@type': 'Organization', name: 'Railhook', url: siteUrl() },
  });

  const ids = useId();
  const [provider, setProvider] = useState<Provider>('standard');
  const [payload, setPayload] = useState('');
  const [secret, setSecret] = useState('');
  const [headers, setHeaders] = useState<HeaderValues>(EMPTY_HEADERS);
  const [result, setResult] = useState<VerifyResult>({ status: 'incomplete' });

  const scheme = schemeOf(provider);
  const values = headers[provider];

  // Recomputed as the reader types; a slower earlier check never overwrites a later one.
  useEffect(() => {
    let current = true;
    verifySignature({ provider, payload, secret, headers: values }).then((next) => {
      if (current) setResult(next);
    });
    return () => {
      current = false;
    };
  }, [provider, payload, secret, values]);

  const setHeader = (name: string, value: string) =>
    setHeaders((all) => ({ ...all, [provider]: { ...all[provider], [name]: value } }));

  const fillExample = async () => {
    const exampleSecret = EXAMPLE_SECRETS[provider];
    const signed = await exampleHeaders(provider, EXAMPLE_PAYLOAD, exampleSecret);
    setPayload(EXAMPLE_PAYLOAD);
    setSecret(exampleSecret);
    setHeaders((all) => ({ ...all, [provider]: signed }));
  };

  const clear = () => {
    setPayload('');
    setSecret('');
    setHeaders(EMPTY_HEADERS);
  };

  const field = (name: string) => `${ids}-${name}`;

  return (
    <>
      <PageIntro eyebrow={t('webhookSignature.eyebrow')} title={t('webhookSignature.title')} lead={t('webhookSignature.lead')}>
        <p className="inline-flex max-w-full items-start gap-2 border border-primary bg-accent px-3.5 py-2.5 text-sm font-medium text-accent-foreground">
          <ShieldCheck className="mt-0.5 h-4 w-4 flex-none text-primary" aria-hidden="true" />
          {t('webhookSignature.private')}
        </p>
      </PageIntro>

      <Band labelledBy="verifier-tool">
        <h2 id="verifier-tool" className="sr-only">{t('webhookSignature.title')}</h2>
        <div className="grid gap-8 lg:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)]">
          <form className="grid min-w-0 gap-6" onSubmit={(e) => e.preventDefault()} autoComplete="off">
            <ProviderPicker value={provider} onChange={setProvider} />

            <div className="grid gap-2">
              <Label htmlFor={field('secret')}>{t('webhookSignature.secret')}</Label>
              <Input
                id={field('secret')}
                value={secret}
                onChange={(e) => setSecret(e.target.value)}
                spellCheck={false}
                autoComplete="off"
                data-1p-ignore
                aria-describedby={field('secret-hint')}
                className="font-mono"
              />
              <p id={field('secret-hint')} className="text-xs text-muted-foreground">
                {t(`webhookSignature.secretHint.${provider}`)}
              </p>
            </div>

            <fieldset className="grid gap-3">
              <legend className="mb-1 text-sm font-medium text-foreground">{t('webhookSignature.headers')}</legend>
              {scheme.headers.map((name) => (
                <div key={name} className="grid gap-1.5">
                  <Label htmlFor={field(name)} className="font-mono text-[12.5px] font-normal text-muted-foreground">
                    {name}
                  </Label>
                  <Input
                    id={field(name)}
                    value={values[name] ?? ''}
                    onChange={(e) => setHeader(name, e.target.value)}
                    spellCheck={false}
                    autoComplete="off"
                    className="font-mono"
                  />
                </div>
              ))}
              <p className="text-xs text-muted-foreground">{t('webhookSignature.headersHint')}</p>
            </fieldset>

            <div className="grid gap-2">
              <Label htmlFor={field('payload')}>{t('webhookSignature.payload')}</Label>
              <Textarea
                id={field('payload')}
                value={payload}
                onChange={(e) => setPayload(e.target.value)}
                spellCheck={false}
                rows={8}
                aria-describedby={field('payload-hint')}
                // 16px on a phone: iOS Safari zooms into any focused field smaller than that,
                // and a zoomed page is a page the reader then has to pan back.
                className="font-mono text-base sm:text-[13px]"
              />
              <p id={field('payload-hint')} className="text-xs text-muted-foreground">{t('webhookSignature.payloadHint')}</p>
            </div>

            <div className="flex flex-wrap gap-2.5">
              <Button type="button" variant="outline" onClick={fillExample}>{t('webhookSignature.example')}</Button>
              <Button type="button" variant="ghost" onClick={clear}>{t('webhookSignature.clear')}</Button>
            </div>
          </form>

          <div className="min-w-0 lg:sticky lg:top-24 lg:self-start">
            <section
              role="status"
              aria-live="polite"
              aria-label={t('webhookSignature.result.label')}
              className={cn(
                'border bg-card p-6 transition-colors sm:p-7',
                result.status === 'valid' ? 'border-ok/50' : result.status === 'invalid' ? 'border-halt/50' : 'border-rail',
              )}
            >
              <Result result={result} />
            </section>
          </div>
        </div>
      </Band>

      <Band muted labelledBy="verifier-how">
        <SectionHeading id="verifier-how" title={t('webhookSignature.how.title')} lead={t('webhookSignature.how.lead')} />
        <dl className="grid gap-x-10 gap-y-7 md:grid-cols-2">
          {PROVIDERS.map((p) => (
            <div key={p.id}>
              <dt className="font-medium text-foreground">{t(`webhookSignature.providers.${p.id}`)}</dt>
              <dd className="mt-1.5 text-[15px] leading-relaxed text-muted-foreground [overflow-wrap:anywhere]">
                {t(`webhookSignature.how.${p.id}`)}
              </dd>
            </div>
          ))}
        </dl>
      </Band>

      <Band labelledBy="verifier-cta">
        <div className="flex flex-col items-start justify-between gap-5 md:flex-row md:items-center">
          <div className="max-w-2xl">
            <h2 id="verifier-cta" className="text-[1.75rem] font-normal leading-[1.16] tracking-[-0.02em] sm:text-[2rem] text-foreground">
              {t('webhookSignature.cta.title')}
            </h2>
            <p className="mt-2 text-muted-foreground">{t('webhookSignature.cta.body')}</p>
          </div>
          <div className="flex flex-wrap gap-2.5">
            <Button asChild>
              <Link to="/register">{t('webhookSignature.cta.button')}</Link>
            </Button>
            <Button asChild variant="outline">
              <a href="/docs/incoming/verification/">{t('webhookSignature.cta.docs')}</a>
            </Button>
          </div>
        </div>
      </Band>
    </>
  );
}
