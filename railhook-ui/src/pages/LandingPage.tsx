import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import HeroSection from './landing/HeroSection';
import ProductSection from './landing/ProductSection';
import ReliabilitySection from './landing/ReliabilitySection';
import SelfHostSection from './landing/SelfHostSection';
import FinalSection from './landing/FinalSection';
import { IN, LabelBand, Rule } from './landing/parts';
import { useDocumentMeta } from '../hooks/useDocumentMeta';

const PROVIDERS = ['Stripe', 'GitHub', 'Shopify', 'Slack', 'Twilio', 'SendGrid'];

export default function LandingPage() {
  const { t } = useTranslation();
  const { hash } = useLocation();

  useDocumentMeta({ titleKey: 'meta.landing.title', descriptionKey: 'meta.landing.description', path: '/' });

  // A router navigation to "/#id" does not scroll on its own.
  useEffect(() => {
    if (!hash) {
      window.scrollTo({ top: 0, behavior: 'auto' });
      return;
    }
    const target = document.getElementById(hash.slice(1));
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, [hash]);

  return (
    <>
      <HeroSection />

      <Rule className="mt-20" />
      <LabelBand>{t('landing.providers.label')}</LabelBand>
      <Rule />
      <ul className={`${IN} grid grid-cols-4 items-center gap-y-[22px] py-[22px] min-[901px]:h-[92px] min-[901px]:grid-cols-7 min-[901px]:py-0`}>
        {PROVIDERS.map((name) => (
          <li key={name} className="text-center text-[17px] font-medium leading-none tracking-[-0.01em] text-[#4A4A4A] dark:text-muted-foreground">
            {name}
          </li>
        ))}
        <li className="text-center font-mono text-xs uppercase leading-none text-[#777] dark:text-muted-foreground">{t('landing.providers.any')}</li>
      </ul>
      <Rule />

      <ProductSection />
      <ReliabilitySection />
      <SelfHostSection />
      <FinalSection />
    </>
  );
}
