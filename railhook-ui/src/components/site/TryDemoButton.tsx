import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PlayCircle } from 'lucide-react';
import { Button, type ButtonProps } from '../ui/button';
import { useAuth } from '../../auth/auth.store';
import { publicDemoEnabled } from '../../lib/runtimeConfig';

/**
 * "Try the live demo": a secondary way in, next to signing up. Only where the deployment runs
 * the demo, and only for a visitor who is not signed in — someone with an account has the real
 * thing one click away.
 */
export default function TryDemoButton({ className, size, variant = 'ghost' }: Pick<ButtonProps, 'className' | 'size' | 'variant'>) {
  const { t } = useTranslation();
  const { isAuthenticated } = useAuth();

  if (!publicDemoEnabled() || isAuthenticated) return null;

  return (
    <Button asChild size={size} variant={variant} className={className}>
      <Link to="/demo">
        <PlayCircle className="h-4 w-4" aria-hidden="true" />
        {t('landing.hero.tryDemo')}
      </Link>
    </Button>
  );
}
