import { type ReactElement, cloneElement } from 'react';
import { useTranslation } from 'react-i18next';
import { Tooltip } from './ui/tooltip';
import { usePermissions } from '../auth/usePermissions';

interface VerificationGateProps {
  /** Fallback: 'disable' shows disabled + tooltip, 'hide' hides entirely */
  fallback?: 'disable' | 'hide';
  /** Override tooltip text */
  tooltip?: string;
  children: ReactElement;
}

/**
 * Gates critical write actions behind email verification.
 * When the user's email is not verified, the child element is disabled with a tooltip,
 * or hidden entirely depending on the fallback prop.
 */
export default function VerificationGate({
  fallback = 'disable',
  tooltip,
  children,
}: VerificationGateProps) {
  const { t } = useTranslation();
  const { emailVerified } = usePermissions();

  if (emailVerified) return children;

  if (fallback === 'hide') return null;

  const tooltipText = tooltip || t('auth.verification.gateTooltip');

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
