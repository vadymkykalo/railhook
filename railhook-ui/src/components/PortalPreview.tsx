import { useTranslation } from 'react-i18next';
import { useIsDarkTheme } from '../hooks/useIsDarkTheme';

/**
 * A still of the embedded portal, for a project that has no consumers yet: "consumer" and
 * "portal session" say nothing until you have seen what your own user would be looking at.
 * The same capture the landing page shows, in the theme the dashboard is in.
 */
export default function PortalPreview() {
  const { t } = useTranslation();
  const isDark = useIsDarkTheme();

  return (
    <figure className="overflow-hidden rounded-lg border border-rail bg-card">
      <img
        src={`/screens/portal-${isDark ? 'dark' : 'light'}.webp`}
        alt={t('landing.product.portal.alt')}
        width={1440}
        height={900}
        loading="lazy"
        className="block h-auto w-full"
      />
      <figcaption className="border-t border-rail px-4 py-2.5 text-xs text-muted-foreground">
        {t('consumers.preview.caption')}
      </figcaption>
    </figure>
  );
}
