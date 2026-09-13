import { ClipboardList, Inbox, RotateCw, type LucideIcon } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Band, SectionHeading } from './primitives';

/**
 * What happens when the other side is down, as three plain facts. The retry schedule, ordering and
 * replay are real and documented; on this page they would be mechanism, and the reader wants the
 * outcome.
 */
function Fact({ icon: Icon, title, body }: { icon: LucideIcon; title: string; body: string }) {
  return (
    // On a phone the icon sits beside the title and the text hangs under it, so each fact is one
    // compact block instead of three loose rows; from sm the stack is as it was.
    <div className="grid content-start gap-2 max-sm:grid-cols-[38px_minmax(0,1fr)] max-sm:gap-x-3.5 max-sm:gap-y-1">
      <span className="grid h-[38px] w-[38px] place-items-center rounded-[10px] bg-accent text-primary">
        <Icon className="h-5 w-5" aria-hidden="true" />
      </span>
      <h3 className="mt-1 text-[1.08rem] font-semibold text-foreground max-sm:mt-0 max-sm:self-center">{title}</h3>
      <p className="text-muted-foreground max-sm:col-start-2">{body}</p>
    </div>
  );
}

export default function ReliabilitySection() {
  const { t } = useTranslation();
  return (
    <Band labelledBy="reliability-title">
      <SectionHeading id="reliability-title" title={t('landing.reliability.title')} lead={t('landing.reliability.lead')} />
      <div className="grid gap-7 border-t border-rail pt-7 sm:gap-9 sm:pt-8 md:grid-cols-3">
        <Fact icon={RotateCw} title={t('landing.reliability.retryTitle')} body={t('landing.reliability.retryBody')} />
        <Fact icon={Inbox} title={t('landing.reliability.failedTitle')} body={t('landing.reliability.failedBody')} />
        <Fact icon={ClipboardList} title={t('landing.reliability.recordTitle')} body={t('landing.reliability.recordBody')} />
      </div>
    </Band>
  );
}
