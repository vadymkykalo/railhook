import { type ReactElement, cloneElement } from 'react';
import { useTranslation } from 'react-i18next';
import { Crown, Wrench, Eye, Check } from 'lucide-react';
import { Tooltip } from './ui/tooltip';
import { cn } from '../lib/utils';
import type { Role } from '../auth/usePermissions';

interface PermissionGateProps {
  /** The permission boolean from usePermissions() */
  allowed: boolean;
  /** Minimum role required — shown in the tooltip */
  requiredRole?: Role;
  /** Override tooltip text */
  tooltip?: string;
  /** Fallback: 'disable' shows disabled + tooltip, 'hide' hides entirely */
  fallback?: 'disable' | 'hide';
  children: ReactElement;
}

export default function PermissionGate({
  allowed,
  requiredRole = 'DEVELOPER',
  tooltip,
  fallback = 'disable',
  children,
}: PermissionGateProps) {
  const { t } = useTranslation();

  if (allowed) return children;

  if (fallback === 'hide') return null;

  const tooltipText = tooltip || t('permissions.requiredRole', { role: t(`roles.${requiredRole}.name`) });

  return (
    <Tooltip content={tooltipText} side="top">
      <span className="inline-flex">
        {cloneElement(children, {
          disabled: true,
          'aria-disabled': true,
          className: `${children.props.className || ''} opacity-50 cursor-not-allowed pointer-events-auto`.trim(),
          onClick: (e: React.MouseEvent) => e.preventDefault(),
        })}
      </span>
    </Tooltip>
  );
}

/* ────────────────────────────────────────────────────────────────────────
   The role vocabulary.

   Three roles decide what a person can do, and the difference between them
   has to be readable on the page that grants them — nobody should have to
   open the docs to learn what they just handed a teammate. The capability
   lines below are the UI's restatement of the matrix in usePermissions.ts;
   when that matrix changes, these change with it.
   ──────────────────────────────────────────────────────────────────────── */

export const ROLES: Role[] = ['OWNER', 'DEVELOPER', 'VIEWER'];

/** Roles that can be granted to somebody else. An owner is never handed out from a list. */
export const GRANTABLE_ROLES: Role[] = ['DEVELOPER', 'VIEWER'];

export const ROLE_ICON: Record<Role, React.ElementType> = {
  OWNER: Crown,
  DEVELOPER: Wrench,
  VIEWER: Eye,
};

/** How many capability bullets each role carries in the locale file. */
const ROLE_BULLETS: Record<Role, number> = { OWNER: 3, DEVELOPER: 3, VIEWER: 3 };

export function roleCapabilities(role: Role): string[] {
  return Array.from({ length: ROLE_BULLETS[role] }, (_, i) => `roles.${role}.can${i + 1}`);
}

/**
 * One role, explained. Rendered as a static card in a legend, or as a radio
 * when `onSelect` is given — the same explanation either way, so choosing a
 * role and reading about one are never two different descriptions.
 */
export function RoleCard({
  role, count, selected, onSelect, disabled,
}: {
  role: Role;
  /** Members currently holding this role, when the card is a legend entry. */
  count?: number;
  selected?: boolean;
  onSelect?: (role: Role) => void;
  disabled?: boolean;
}) {
  const { t } = useTranslation();
  const Icon = ROLE_ICON[role];
  const interactive = !!onSelect;

  const body = (
    <>
      <div className="flex items-center gap-2">
        <Icon className={cn('h-4 w-4 flex-shrink-0', selected ? 'text-primary' : 'text-muted-foreground')} aria-hidden />
        <span className="text-sm font-medium">{t(`roles.${role}.name`)}</span>
        {count !== undefined && (
          <span className="ml-auto font-mono text-[13px] text-muted-foreground">{count}</span>
        )}
        {selected && interactive && <Check className="ml-auto h-4 w-4 flex-shrink-0 text-primary" aria-hidden />}
      </div>
      <p className="mt-1.5 text-[13px] leading-snug text-muted-foreground">{t(`roles.${role}.summary`)}</p>
      <ul className="mt-2.5 space-y-1">
        {roleCapabilities(role).map((key) => (
          <li key={key} className="flex gap-1.5 text-[12px] leading-snug text-muted-foreground">
            <span aria-hidden className="text-rail">—</span>
            <span>{t(key)}</span>
          </li>
        ))}
      </ul>
    </>
  );

  if (!interactive) {
    return <div className="rounded-lg border border-rail bg-card p-4">{body}</div>;
  }

  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      disabled={disabled}
      onClick={() => onSelect(role)}
      className={cn(
        'rounded-lg border p-4 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2',
        'disabled:cursor-not-allowed disabled:opacity-50',
        selected ? 'border-primary bg-accent/40' : 'border-rail bg-card hover:border-primary/40'
      )}
    >
      {body}
    </button>
  );
}
