import { useId, useState, type FormEvent } from 'react';
import { useMutation } from '@tanstack/react-query';
import { useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { CheckCircle2, Loader2, Send } from 'lucide-react';
import { contactApi, type ContactTopic } from '../../api/contact.api';
import CaptchaWidget, { isCaptchaConfigured } from '../CaptchaWidget';
import { Button } from '../ui/button';
import { Input } from '../ui/input';
import { Label } from '../ui/label';
import { Textarea } from '../ui/textarea';
import { cn } from '../../lib/utils';

const TOPICS: ContactTopic[] = ['other', 'sales', 'support'];
const MAX_MESSAGE = 5000;

function errorOf(error: unknown): { status?: number; code?: string } {
  const response = (error as { response?: { status?: number; data?: { error?: string } } } | null)?.response;
  return { status: response?.status, code: response?.data?.error };
}

/** Why the message did not go, in the reader's words. */
function errorKey(error: unknown): string {
  const { status, code } = errorOf(error);
  if (code === 'captcha_failed') return 'site.contact.errors.captcha';
  if (status === 429) return 'site.contact.errors.tooMany';
  if (status === 400) return 'site.contact.errors.invalid';
  return 'site.contact.errors.generic';
}

/**
 * Write to support without leaving the page: the widget in the corner and the contact page both
 * render this. The server mails the deployment's own support address with the visitor's as the
 * Reply-To, so the answer arrives in their inbox like any other mail.
 */
export default function ContactForm({ compact = false, autoFocus = false }: { compact?: boolean; autoFocus?: boolean }) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const ids = useId();
  const [topic, setTopic] = useState<ContactTopic>('other');
  const [email, setEmail] = useState('');
  const [name, setName] = useState('');
  const [message, setMessage] = useState('');
  const [captchaToken, setCaptchaToken] = useState('');

  const send = useMutation({
    mutationFn: () =>
      contactApi.send({
        email: email.trim(),
        name: name.trim() || undefined,
        topic,
        message: message.trim(),
        page: pathname,
        captchaToken: captchaToken || undefined,
      }),
    // A token is single-use: after a refusal the widget remounts and asks again.
    onError: () => setCaptchaToken(''),
  });

  const needsChallenge = isCaptchaConfigured() && !captchaToken;

  if (send.isSuccess) {
    return (
      <div role="status" className="flex flex-col items-center px-2 py-8 text-center">
        <span className="grid h-12 w-12 place-items-center rounded-full bg-accent text-primary">
          <CheckCircle2 className="h-6 w-6" aria-hidden="true" />
        </span>
        <p className="mt-4 text-[1.05rem] font-semibold text-foreground">{t('site.contact.sentTitle')}</p>
        <p className="mt-1.5 max-w-xs text-sm text-muted-foreground">{t('site.contact.sentBody', { email: email.trim() })}</p>
        <Button
          variant="outline"
          size="sm"
          className="mt-5"
          onClick={() => {
            setMessage('');
            send.reset();
          }}
        >
          {t('site.contact.another')}
        </Button>
      </div>
    );
  }

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (send.isPending || needsChallenge) return;
    send.mutate();
  };

  return (
    <form onSubmit={onSubmit} className={cn('flex flex-col', compact ? 'gap-3' : 'gap-4')} noValidate={false}>
      <fieldset>
        <legend className="mb-2 text-sm font-medium text-foreground">{t('site.contact.topicLabel')}</legend>
        <div className="flex flex-wrap gap-1.5">
          {TOPICS.map((value) => (
            <label
              key={value}
              className={cn(
                'cursor-pointer rounded-full border px-3 py-1 text-sm transition-colors',
                'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring has-[:focus-visible]:ring-offset-1',
                topic === value
                  ? 'border-primary bg-primary text-primary-foreground'
                  : 'border-rail bg-card text-muted-foreground hover:border-primary/50 hover:text-foreground',
              )}
            >
              <input
                type="radio"
                name={`${ids}-topic`}
                value={value}
                checked={topic === value}
                onChange={() => setTopic(value)}
                className="sr-only"
              />
              {t(`site.contact.topics.${value}`)}
            </label>
          ))}
        </div>
      </fieldset>

      <div className={cn('grid gap-3', !compact && 'sm:grid-cols-2')}>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${ids}-email`}>{t('site.contact.emailLabel')}</Label>
          <Input
            id={`${ids}-email`}
            type="email"
            required
            maxLength={254}
            autoComplete="email"
            autoFocus={autoFocus}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t('site.contact.emailPlaceholder')}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${ids}-name`}>
            {t('site.contact.nameLabel')} <span className="font-normal text-muted-foreground">{t('site.contact.optional')}</span>
          </Label>
          <Input
            id={`${ids}-name`}
            maxLength={100}
            autoComplete="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${ids}-message`}>{t('site.contact.messageLabel')}</Label>
        <Textarea
          id={`${ids}-message`}
          required
          maxLength={MAX_MESSAGE}
          rows={compact ? 4 : 6}
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          placeholder={t('site.contact.messagePlaceholder')}
          className="resize-none"
        />
      </div>

      {isCaptchaConfigured() && <CaptchaWidget key={send.failureCount} onToken={setCaptchaToken} />}

      {send.isError && (
        <p role="alert" className="text-sm text-halt">
          {t(errorKey(send.error))}
        </p>
      )}

      <div className={cn('flex gap-3', compact ? 'flex-col-reverse' : 'flex-wrap items-center justify-between')}>
        <p className={cn('text-xs text-muted-foreground', compact && 'text-center')}>{t('site.contact.responseNote')}</p>
        <Button type="submit" disabled={send.isPending || needsChallenge} className={cn(compact ? 'w-full' : 'max-sm:w-full')}>
          {send.isPending ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Send className="h-4 w-4" aria-hidden="true" />}
          {t('site.contact.send')}
        </Button>
      </div>
    </form>
  );
}
