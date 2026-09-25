import { type ReactElement, cloneElement } from 'react';
import { useTranslation } from 'react-i18next';
import { Tooltip } from './ui/tooltip';
import { usePermissions } from '../auth/usePermissions';

interface VerificationGateProps {
  fallback?: 'disable' | 'hide';
  tooltip?: string;
  children: ReactElement;
}

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
