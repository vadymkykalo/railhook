import { BookOpen, Github, LifeBuoy, Mail } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { panel, Reveal, Section } from './landing/primitives';
import { cn } from '../lib/utils';
import { REPO_URL } from './landing/plans';
import { useDocumentMeta } from '../hooks/useDocumentMeta';
import { docsUrl } from '../lib/docsUrl';
import { contactDomain } from '../lib/runtimeConfig';
import ContactForm from '../components/site/ContactForm';

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
 * The domain comes from RAILHOOK_CONTACT_DOMAIN on the UI container and there
 * is no default. It was once a constant naming a domain this project does not
 * own, so every self-hosted install invited its users to write to a stranger
 * about a product that stranger has never heard of.
 *
 * It is read at runtime, not inlined at build: the published image is the same
 * one the hosted cloud runs and every self-hosted install pulls. As a build
 * argument the choice was between no addresses on the cloud's own site or the
 * cloud's addresses on everyone's deployment.
 *
 * With the variable unset the two mail cards are not rendered at all, rather
 * than falling back to the repository. A deployment someone runs for their own
 * company has no sales desk, and an address that reaches nobody is worse than
 * an absent one — the reader who needs a human still has the issues card and
 * the docs card, which are true everywhere.
 */
function mailto(mailbox: string, domain: string): string {
  return `mailto:${mailbox}@${domain}`;
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
      <h2 className="mt-3 text-[15px] font-medium text-foreground">{title}</h2>
      <p className="mt-2 text-[15px] leading-relaxed text-muted-foreground">{body}</p>
      <div className="mt-auto pt-5">{action}</div>
    </div>
  );
}

const LINK = 'text-sm font-medium link-ink';

export default function ContactPage() {
  const { t, i18n } = useTranslation();
  useDocumentMeta({ titleKey: 'meta.contact.title', descriptionKey: 'meta.contact.description', path: '/contact' });
  const domain = contactDomain();

  return (
    <Section ruled={false}>
      <Reveal>
        <div className="max-w-2xl">
          <h1 className="text-[2.375rem] font-normal leading-[1.16] tracking-[-0.03em] text-foreground sm:text-[3.5rem]">
            {t('contact.title')}
          </h1>
          <p className="mt-4 text-body-lg text-muted-foreground">{t('contact.subtitle')}</p>
        </div>
      </Reveal>

      {/* The form writes to the same support address, so it is here only where that exists. */}
      {domain && (
        <Reveal>
          <div className={cn('mt-10 p-6 sm:p-8', panel())}>
            <h2 className="text-[1.5rem] font-normal tracking-[-0.02em] text-foreground">{t('site.contact.formTitle')}</h2>
            <p className="mt-1.5 text-muted-foreground">{t('site.contact.formLead')}</p>
            <div className="mt-6">
              <ContactForm />
            </div>
          </div>
        </Reveal>
      )}

      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        {domain && (
          <>
            <Reveal className="h-full">
              <Card
                icon={Mail}
                title={t('contact.salesTitle')}
                body={t('contact.salesBody')}
                action={
                  <a href={mailto('sales', domain)} className={LINK}>
                    {`sales@${domain}`}
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
                  <a href={mailto('support', domain)} className={LINK}>
                    {`support@${domain}`}
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
              <a href={docsUrl(i18n.language)} className={LINK}>
                {t('contact.docsCta')}
              </a>
            }
          />
        </Reveal>
      </div>

      <p className="mt-6 text-sm text-muted-foreground">{t('contact.responseNote')}</p>
    </Section>
  );
}
