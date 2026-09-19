import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ArrowUpRight, BookOpen, Bot, Braces, LayoutPanelTop, ShieldCheck } from 'lucide-react';
import { Button } from '../../components/ui/button';
import { highlight, type CodeLanguage } from '../../components/SyntaxHighlight';
import { useAuth } from '../../auth/auth.store';
import { cn } from '../../lib/utils';
import { WRAP, prefersReducedMotion } from './primitives';

/**
 * The page ends where an engineer starts: the docs, the API reference, the signature standard,
 * the MCP server for their AI agent, and the one call that sends an event — with sign-up once more underneath.
 *
 * The samples are the quickstart's "Send an event" step, which was checked against `sdks/*`;
 * change them there first. Every line is in the DOM from the start, so a screen reader and a
 * copy both get the whole sample; the typing is only opacity.
 */

type Sample = { id: string; label: string; file: string; language: CodeLanguage; code: string };

const SAMPLES: Sample[] = [
  {
    id: 'node',
    label: 'Node.js',
    file: 'send-event.ts',
    language: 'javascript',
    code: `import { Railhook } from '@railhook/node';

const client = new Railhook({
  apiKey: process.env.RAILHOOK_API_KEY,
  baseUrl: 'https://railhook.io',
});

const event = await client.events.send({
  type: 'order.completed',
  data: { orderId: 'ord_12345', amount: 99.99 },
});
console.log(event.deliveriesCreated);`,
  },
  {
    id: 'python',
    label: 'Python',
    file: 'send_event.py',
    language: 'python',
    code: `import os

from railhook import Railhook, Event

client = Railhook(
    api_key=os.environ["RAILHOOK_API_KEY"],
    base_url="https://railhook.io",
)

event = client.events.send(Event(
    type="order.completed",
    data={"order_id": "ord_12345", "amount": 99.99},
))
print(event.deliveries_created)`,
  },
  {
    id: 'php',
    label: 'PHP',
    file: 'send-event.php',
    language: 'php',
    code: `<?php
use Railhook\\Railhook;

$client = new Railhook(
    apiKey: getenv('RAILHOOK_API_KEY'),
    baseUrl: 'https://railhook.io',
);

$event = $client->events->send(
    type: 'order.completed',
    data: ['orderId' => 'ord_12345', 'amount' => 99.99],
);
echo $event['deliveriesCreated'];`,
  },
  {
    id: 'curl',
    label: 'cURL',
    file: 'send-event.sh',
    language: 'bash',
    code: `curl -X POST https://railhook.io/api/v1/events \\
  -H "X-API-Key: $RAILHOOK_API_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{"type":"order.completed","data":{"orderId":"ord_12345","amount":99.99}}'`,
  },
];

const LINE_INTERVAL_MS = 95;

/** Starts false and flips once, the first time the element is scrolled to. */
function useSeenOnce<T extends Element>() {
  const ref = useRef<T | null>(null);
  const [seen, setSeen] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node || typeof IntersectionObserver === 'undefined') {
      setSeen(true);
      return;
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          setSeen(true);
          observer.disconnect();
        }
      },
      { threshold: 0.3 },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return [ref, seen] as const;
}

/**
 * Follows the reduced-motion preference live rather than reading it once: it can be switched on
 * while the page is open, and the code must then stop typing and show every line.
 */
function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(prefersReducedMotion);

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = () => setReduced(query.matches);
    onChange();
    query.addEventListener?.('change', onChange);
    return () => query.removeEventListener?.('change', onChange);
  }, []);

  return reduced;
}

/** How many lines of the current sample are showing: all of them under reduced motion. */
function useTypedLines(total: number, running: boolean, restartKey: string): number {
  const reduced = useReducedMotion();
  const [count, setCount] = useState(() => (prefersReducedMotion() ? total : 0));

  useEffect(() => {
    if (reduced) {
      setCount(total);
      return;
    }
    setCount(0);
    if (!running) return;
    const timer = window.setInterval(() => {
      setCount((n) => {
        if (n + 1 >= total) window.clearInterval(timer);
        return Math.min(n + 1, total);
      });
    }, LINE_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [reduced, running, total, restartKey]);

  return count;
}

function LinkCard({
  href,
  icon,
  title,
  body,
  external = false,
}: {
  href: string;
  icon: ReactNode;
  title: string;
  body: string;
  external?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <a
      href={href}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      className="group relative flex gap-4 rounded-xl border border-rail bg-card/70 p-5 pr-11 transition-[border-color,background-color,transform] duration-200 hover:-translate-y-0.5 hover:border-primary/60 hover:bg-card motion-reduce:hover:translate-y-0 focus-visible:rounded-xl"
    >
      <span className="grid h-10 w-10 flex-none place-items-center rounded-lg border border-primary/25 bg-accent text-accent-foreground">
        {icon}
      </span>
      <span className="min-w-0">
        <span className="block font-semibold text-foreground">{title}</span>
        <span className="mt-1 block text-sm leading-relaxed text-muted-foreground">{body}</span>
        {external && <span className="sr-only"> ({t('landing.developer.newTab')})</span>}
      </span>
      <ArrowUpRight
        aria-hidden="true"
        className="absolute right-4 top-4 h-4 w-4 text-muted-foreground transition-[color,transform] duration-200 group-hover:-translate-y-0.5 group-hover:translate-x-0.5 group-hover:text-primary motion-reduce:group-hover:translate-x-0 motion-reduce:group-hover:translate-y-0"
      />
    </a>
  );
}

function CodeWindow() {
  const { t } = useTranslation();
  const baseId = useId();
  const [active, setActive] = useState(0);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);
  const [windowRef, seen] = useSeenOnce<HTMLDivElement>();

  const sample = SAMPLES[active];
  const lines = sample.code.split('\n');
  const typed = useTypedLines(lines.length, seen, sample.id);
  const done = typed >= lines.length;

  const select = (index: number) => {
    const next = (index + SAMPLES.length) % SAMPLES.length;
    setActive(next);
    tabRefs.current[next]?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const moves: Record<string, number> = {
      ArrowRight: active + 1,
      ArrowLeft: active - 1,
      Home: 0,
      End: SAMPLES.length - 1,
    };
    if (event.key in moves) {
      event.preventDefault();
      select(moves[event.key]);
    }
  };

  const tabId = (i: number) => `${baseId}-tab-${i}`;
  const panelId = `${baseId}-panel`;

  return (
    <div ref={windowRef} className="min-w-0">
      <div className="relative">
        <div aria-hidden="true" className="dev-halo" />
        <div className="dev-glow">
          <div className="relative overflow-hidden rounded-[15px] bg-[hsl(231.4_36.8%_3.7%)]">
            <div className="flex items-center justify-between gap-3 border-b border-rail px-4 py-3 sm:px-5">
              <p className="min-w-0 text-sm font-semibold leading-snug text-foreground">{t('landing.developer.codeTitle')}</p>
              <span className="flex-none rounded-md border border-primary/30 bg-accent px-2 py-0.5 font-mono text-[11.5px] text-accent-foreground">
                {sample.file}
              </span>
            </div>
            <div
              id={panelId}
              role="tabpanel"
              aria-labelledby={tabId(active)}
              tabIndex={0}
              className="overflow-x-auto px-4 py-4 sm:px-5 sm:py-5"
            >
              {/* No ligatures: JetBrains Mono draws `->` and `=>` as arrows, which is not what the reader types. */}
              <pre className="min-h-[15.5rem] font-mono text-[12.5px] leading-[1.7] [font-variant-ligatures:none] sm:text-[13px]">
                <code className="block w-max min-w-full">
                  {lines.map((line, i) => (
                    <span
                      key={`${sample.id}-${i}`}
                      className={cn(
                        'grid grid-cols-[2ch_auto] gap-4 transition-[opacity,transform] duration-300 ease-out motion-reduce:transition-none',
                        i < typed ? 'translate-x-0 opacity-100' : '-translate-x-1 opacity-0',
                      )}
                    >
                      <span aria-hidden="true" className="select-none text-right text-muted-foreground/60">{i + 1}</span>
                      <span className="text-foreground">
                        {line ? highlight(line, sample.language) : ' '}
                        {!done && i === typed - 1 && <span aria-hidden="true" className="dev-caret" />}
                      </span>
                    </span>
                  ))}
                </code>
              </pre>
            </div>
          </div>
        </div>
      </div>

      <p
        className={cn(
          'mt-3 h-5 px-1 font-mono text-[12.5px] text-muted-foreground transition-opacity duration-500 motion-reduce:transition-none',
          done ? 'opacity-100 delay-200' : 'opacity-0',
        )}
      >
        <span aria-hidden="true" className="mr-2 text-muted-foreground/60">←</span>
        <span className="text-ok">201 Created</span>
        <span> · {t('landing.developer.response')}</span>
      </p>

      <div
        role="tablist"
        aria-label={t('landing.developer.languages')}
        className="-mx-1 mt-4 flex gap-2 overflow-x-auto px-1 py-1 sm:justify-center"
      >
        {SAMPLES.map((s, i) => (
          <button
            key={s.id}
            ref={(node) => { tabRefs.current[i] = node; }}
            id={tabId(i)}
            type="button"
            role="tab"
            aria-selected={i === active}
            aria-controls={panelId}
            tabIndex={i === active ? 0 : -1}
            onClick={() => setActive(i)}
            onKeyDown={onKeyDown}
            className={cn(
              'flex-none rounded-lg border px-3.5 py-1.5 font-mono text-[12.5px] transition-colors max-sm:h-10 max-sm:px-4',
              i === active
                ? 'border-primary/60 bg-accent text-accent-foreground'
                : 'border-rail text-muted-foreground hover:border-muted-foreground hover:text-foreground',
            )}
          >
            {s.label}
          </button>
        ))}
      </div>
    </div>
  );
}

export default function DeveloperSection() {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  return (
    <section
      id="developers"
      aria-labelledby="developer-title"
      className="surface-ink relative isolate scroll-mt-16 overflow-hidden border-t border-rail py-20 sm:py-28"
    >
      <div aria-hidden="true" className="dev-band-arc" />
      <div aria-hidden="true" className="dev-band-arc dev-band-arc--low" />

      <div className={WRAP}>
        <div className="mx-auto mb-12 max-w-2xl text-center sm:mb-14">
          <h2
            id="developer-title"
            className="font-display text-[clamp(1.75rem,3.6vw,2.75rem)] font-bold leading-[1.1] tracking-[-0.025em] text-foreground [text-wrap:balance]"
          >
            {t('landing.developer.title')}
          </h2>
          <p className="mt-3 text-[1.05rem] text-muted-foreground [text-wrap:balance]">{t('landing.developer.lead')}</p>
        </div>

        <div className="grid items-center gap-10 lg:grid-cols-[minmax(0,5fr)_minmax(0,7fr)] lg:gap-14">
          <div className="grid gap-4">
            <LinkCard
              href="/docs/"
              icon={<BookOpen className="h-5 w-5" aria-hidden="true" />}
              title={t('landing.developer.docsTitle')}
              body={t('landing.developer.docsBody')}
            />
            <LinkCard
              href="/docs/api-reference/"
              icon={<Braces className="h-5 w-5" aria-hidden="true" />}
              title={t('landing.developer.apiTitle')}
              body={t('landing.developer.apiBody')}
            />
            <LinkCard
              href="https://www.standardwebhooks.com/"
              external
              icon={<ShieldCheck className="h-5 w-5" aria-hidden="true" />}
              title={t('landing.developer.standardTitle')}
              body={t('landing.developer.standardBody')}
            />
            <LinkCard
              href="/docs/tools/mcp/"
              icon={<Bot className="h-5 w-5" aria-hidden="true" />}
              title={t('landing.developer.mcpTitle')}
              body={t('landing.developer.mcpBody')}
            />
            <LinkCard
              href="/docs/outgoing/customer-portal/"
              icon={<LayoutPanelTop className="h-5 w-5" aria-hidden="true" />}
              title={t('landing.developer.portalTitle')}
              body={t('landing.developer.portalBody')}
            />
          </div>
          <CodeWindow />
        </div>

        <div className="mt-14 flex flex-wrap justify-center gap-3">
          <Button asChild size="lg">
            {isAuthenticated ? (
              <Link to="/admin/dashboard">{t('landing.nav.goToDashboard')}</Link>
            ) : (
              <Link to="/register">{t('landing.hero.startFree')}</Link>
            )}
          </Button>
          <Button asChild size="lg" variant="outline">
            <a href="/docs/">{t('landing.developer.readDocs')}</a>
          </Button>
        </div>
      </div>
    </section>
  );
}
