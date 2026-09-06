import PricingSection from './landing/PricingSection';
import FaqSection from './landing/FaqSection';
import ClosingSection from './landing/ClosingSection';
import { useDocumentMeta } from '../hooks/useDocumentMeta';

/**
 * Pricing on a URL of its own.
 *
 * It was an anchor on the landing page and nothing else, which meant the one
 * page people paste into a chat, send to a finance team or point an ad at could
 * only be reached by scrolling past six sections — and could not be indexed,
 * titled or described separately from the home page.
 *
 * The sections are the same components the landing page renders. A second copy
 * of the plan table is how the numbers in one of them go stale.
 */
export default function PricingPage() {
  useDocumentMeta({ titleKey: 'meta.pricing.title', descriptionKey: 'meta.pricing.description', path: '/pricing' });

  return (
    <>
      <PricingSection />
      <FaqSection />
      <ClosingSection />
    </>
  );
}
