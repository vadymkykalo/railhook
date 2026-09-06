import { useTranslation } from 'react-i18next';
import { RailRule } from './primitives';

/**
 * The band under the fold that answers "is this real?".
 *
 * Every claim here has to be checkable by the reader in one click — the licence
 * file, the published packages, the committed spec, the compose file. The
 * conventional thing to put in this slot is customer logos or a delivery count;
 * we have neither yet, and inventing them is the one thing a landing page can
 * do that costs more than saying nothing. When there are real customers this
 * band is where they go.
 */
const ITEMS = ['item1', 'item2', 'item3', 'item4', 'item5', 'item6'] as const;

export default function TrustBar() {
  const { t } = useTranslation();

  return (
    <section aria-label={t('landing.trustBar.item1')} className="relative">
      <RailRule />
      <div className="mx-auto max-w-6xl px-5 py-5 sm:px-6">
        <ul className="flex flex-wrap items-center gap-x-6 gap-y-2 font-mono text-[11.5px] text-muted-foreground">
          {ITEMS.map((key) => (
            <li key={key} className="flex items-center gap-2">
              <span aria-hidden="true" className="h-1 w-1 rounded-full bg-primary/70" />
              {t(`landing.trustBar.${key}`)}
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
