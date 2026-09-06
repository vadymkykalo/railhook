import { Link } from 'react-router-dom';
import { BookOpen, Github, LifeBuoy, Mail } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { panel, Reveal, Section } from './landing/primitives';
import { cn } from '../lib/utils';
import { REPO_URL } from './landing/plans';
import { useDocumentMeta } from '../hooks/useDocumentMeta';

/**
 * The route that replaces a mailto to a personal Gmail address.
 *
 * Two places on the pricing section used to link
 * `mailto:vadymkykalo@gmail.com?subject=Railhook Enterprise` — the largest deal
 * on the page priced at "Custom" and then routed to an inbox that reads as a
 * side project, with the address itself published for anything that scrapes.
 *
 * Addresses are role accounts and are assembled at render rather than written
 * into the markup, which stops the cheapest scrapers without hiding anything
 * from a reader or a screen reader.
 *
 * The domain comes from VITE_CONTACT_DOMAIN and there is no default. It was a
 * constant naming a domain this project does not own, so every self-hosted
 * install invited its users to write to a stranger about a product that
 * stranger has never heard of.
 *
 * With the variable unset the two mail cards are not rendered at all, rather
 * than falling back to the repository. A deployment someone runs for their own
 * company has no sales desk, and an address that reaches nobody is worse than
 * an absent one — the reader who needs a human still has the issues card and
 * the docs card, which are true everywhere.
 */
const CONTACT_DOMAIN = (import.meta.env.VITE_CONTACT_DOMAIN as string | undefined)?.trim();

function mailto(mailbox: string): string {
  return `mailto:${mailbox}@${CONTACT_DOMAIN}`;
}

function Card({
  icon: Icon,
  title,
  body,
  action,
}: {
  icon: typeof Mail;
  title: string;
  body: string;
  action: React.ReactNode;
}) {
  return (
    <div className={cn('flex h-full flex-col p-6', panel(true))}>
      <Icon className="h-4 w-4 text-primary" aria-hidden="true" />
      <h2 className="mt-3 text-[15px] font-semibold text-foreground">{title}</h2>
      <p className="mt-2 text-[15px] leading-relaxed text-muted-foreground">{body}</p>
      <div className="mt-auto pt-5">{action}</div>
    </div>
  );
}

const LINK = 'text-sm font-medium text-primary hover:underline';

export default function ContactPage() {
  const { t } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.contact.title', descriptionKey: 'meta.contact.description', path: '/contact' });

  return (
    <Section ruled={false}>
      <Reveal>
        <div className="max-w-2xl">
          <h1 className="font-display text-3xl leading-[1.1] tracking-tight text-foreground sm:text-headline">
            {t('contact.title')}
          </h1>
          <p className="mt-4 text-body-lg text-muted-foreground">{t('contact.subtitle')}</p>
        </div>
      </Reveal>

      <div className="mt-10 grid gap-4 sm:grid-cols-2">
        {CONTACT_DOMAIN && (
          <>
            <Reveal className="h-full">
              <Card
                icon={Mail}
                title={t('contact.salesTitle')}
                body={t('contact.salesBody')}
                action={
                  <a href={mailto('sales')} className={LINK}>
                    {`sales@${CONTACT_DOMAIN}`}
                  </a>
                }
              />
            </Reveal>
            <Reveal className="h-full" delay={60}>
              <Card
                icon={LifeBuoy}
                title={t('contact.supportTitle')}
                body={t('contact.supportBody')}
                action={
                  <a href={mailto('support')} className={LINK}>
                    {`support@${CONTACT_DOMAIN}`}
                  </a>
                }
              />
            </Reveal>
          </>
        )}
        <Reveal className="h-full" delay={120}>
          <Card
            icon={Github}
            title={t('contact.communityTitle')}
            body={t('contact.communityBody')}
            action={
              <a href={`${REPO_URL}/issues`} target="_blank" rel="noopener noreferrer" className={LINK}>
                {t('contact.communityCta')}
              </a>
            }
          />
        </Reveal>
        <Reveal className="h-full" delay={180}>
          <Card
            icon={BookOpen}
            title={t('contact.docsTitle')}
            body={t('contact.docsBody')}
            action={
              <Link to="/docs" className={LINK}>
                {t('contact.docsCta')}
              </Link>
            }
          />
        </Reveal>
      </div>

      <p className="mt-6 text-sm text-muted-foreground">{t('contact.responseNote')}</p>
    </Section>
  );
}
