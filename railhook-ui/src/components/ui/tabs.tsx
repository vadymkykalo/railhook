import { useCallback, useId, useRef, type ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

/**
 * An in-page tab strip.
 *
 * Hand-rolled rather than another Radix package, because the visual language
 * already exists — `ModeSwitch` in `Workbench.tsx` is the same control — and what
 * `ModeSwitch` is missing is only the ARIA roles and the arrow-key behaviour
 * that make a *tab* strip a tab strip. `ModeSwitch` stays what it is: a choice
 * of mode, where each option changes what the page does. These are views of one
 * result, where each panel shows the same run from a different angle, and a
 * screen reader should be told which.
 */

export interface TabDefinition<T extends string> {
  value: T;
  label: string;
  icon?: LucideIcon;
  /** A count or a dot beside the label — "3" console lines, "2" errors. */
  badge?: ReactNode;
  /** Draws the badge in the halt colour. For errors, not for counts. */
  badgeAlarming?: boolean;
}

interface TabsProps<T extends string> {
  value: T;
  onChange: (value: T) => void;
  tabs: Array<TabDefinition<T>>;
  ariaLabel: string;
  /** Rendered at the right-hand end of the strip: a copy button, a metric. */
  actions?: ReactNode;
  className?: string;
}

export function Tabs<T extends string>({
  value, onChange, tabs, ariaLabel, actions, className = '',
}: TabsProps<T>) {
  const baseId = useId();
  const stripRef = useRef<HTMLDivElement>(null);

  // Left/Right move between tabs and Home/End jump to the ends, which is what
  // the tab role promises and what keyboard users will try.
  const onKeyDown = useCallback((event: React.KeyboardEvent) => {
    const keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
    if (!keys.includes(event.key)) return;
    event.preventDefault();
    const index = tabs.findIndex((tab) => tab.value === value);
    let next = index;
    if (event.key === 'ArrowLeft') next = (index - 1 + tabs.length) % tabs.length;
    if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
    if (event.key === 'Home') next = 0;
    if (event.key === 'End') next = tabs.length - 1;
    onChange(tabs[next].value);
    const buttons = stripRef.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]');
    buttons?.[next]?.focus();
  }, [tabs, value, onChange]);

  return (
    <div className={`flex items-center justify-between gap-2 ${className}`}>
      <div
        ref={stripRef}
        role="tablist"
        aria-label={ariaLabel}
        onKeyDown={onKeyDown}
        className="flex min-w-0 flex-1 items-center overflow-x-auto border-b border-rail"
      >
        {tabs.map((tab) => {
          const selected = tab.value === value;
          const Icon = tab.icon;
          return (
            <button
              key={tab.value}
              type="button"
              role="tab"
              id={`${baseId}-tab-${tab.value}`}
              aria-selected={selected}
              aria-controls={`${baseId}-panel-${tab.value}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(tab.value)}
              className={`-mb-px inline-flex shrink-0 items-center gap-1.5 border-b-2 px-3 py-2 text-xs font-medium transition-colors ${
                selected
                  ? 'border-foreground text-foreground'
                  : 'border-transparent text-muted-foreground hover:text-foreground'
              }`}
            >
              {Icon ? <Icon className="h-3.5 w-3.5" aria-hidden="true" /> : null}
              <span>{tab.label}</span>
              {tab.badge !== undefined && tab.badge !== null ? (
                <span
                  className={`ml-0.5 px-1.5 py-0.5 text-[10px] font-medium tabular-nums ${
                    tab.badgeAlarming
                      ? 'bg-halt/15 text-halt'
                      : 'bg-muted text-muted-foreground'
                  }`}
                >
                  {tab.badge}
                </span>
              ) : null}
            </button>
          );
        })}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-1">{actions}</div> : null}
    </div>
  );
}

interface TabPanelProps<T extends string> {
  value: T;
  active: T;
  children: ReactNode;
  className?: string;
}

/**
 * Kept mounted and hidden rather than unmounted, so switching tabs does not
 * throw away an editor's scroll position mid-debug — the loop is edit, run,
 * look at three views of the same run, and each look should be instant.
 */
export function TabPanel<T extends string>({ value, active, children, className = '' }: TabPanelProps<T>) {
  const selected = value === active;
  return (
    <div
      role="tabpanel"
      hidden={!selected}
      aria-hidden={!selected}
      className={selected ? className : 'hidden'}
    >
      {children}
    </div>
  );
}
