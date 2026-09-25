import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Button, type ButtonProps } from '../ui/button';
import { useAuth } from '../../auth/auth.store';
import { publicDemoEnabled } from '../../lib/runtimeConfig';

export default function TryDemoButton({
  className,
  size,
  variant = 'outline',
  label,
}: Pick<ButtonProps, 'className' | 'size' | 'variant'> & { label?: string }) {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  if (!publicDemoEnabled() || isAuthenticated) return null;

  return (
    <Button asChild size={size} variant={variant} className={className}>
      <Link to="/demo">
        <span aria-hidden="true" className="-mr-0.5 text-[17px] leading-none">›</span>
        {label ?? t('landing.hero.tryDemo')}
      </Link>
    </Button>
  );
}
