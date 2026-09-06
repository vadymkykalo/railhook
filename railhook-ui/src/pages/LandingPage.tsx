import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import HeroSection from './landing/HeroSection';
import TrustBar from './landing/TrustBar';
import ProblemSection from './landing/ProblemSection';
import DirectionsSection from './landing/DirectionsSection';
import ReliabilitySection from './landing/ReliabilitySection';
import ProductSection from './landing/ProductSection';
import CapabilitiesSection from './landing/CapabilitiesSection';
import SecuritySection from './landing/SecuritySection';
import StartSection from './landing/StartSection';
import PricingSection from './landing/PricingSection';
import FaqSection from './landing/FaqSection';
import ClosingSection from './landing/ClosingSection';
import { useDocumentMeta } from '../hooks/useDocumentMeta';

/**
 * Twelve sections, in the order a reader who has not yet decided needs them:
 * the promise (hero), why they should believe it at all (trust bar), why not
 * build it themselves (problem), which direction is theirs (directions), what
 * happens when the far end is down (reliability), what the admin looks like
 * (product), what it does to a webhook (capabilities), whether it can be
 * trusted with a payload (security), how long the first one takes (start),
 * what it costs (pricing), the objections left (faq), and the ask (closing).
 *
 * The page it replaced had seven, all of them describing the system to a reader
 * assumed to have already chosen it. It opened with the hero and then spent the
 * second slot — the most valuable one on the page — on Postgres, Kafka and
 * Redis. The runtime marks now sit in the self-hosted pricing card, where they
 * are an argument rather than a fact, and the verified providers sit inside the
 * incoming card, where they answer a question the reader has just asked.
 *
 * An earlier version also ended on pricing with no closing CTA, on the reasoning
 * that the sticky nav keeps one on screen. That holds for a reader who is
 * scanning; it does not hold for one who has just read the plans and reached
 * the end of the page.
 */
export default function LandingPage() {
  const { hash } = useLocation();

  useDocumentMeta({ titleKey: 'meta.landing.title', descriptionKey: 'meta.landing.description', path: '/' });

  /* The nav's section links are client-side navigations to "/#id", so nothing
     scrolls on its own — including the case where the reader was already on
     this page and only the hash changed. */
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
      <TrustBar />
      <ProblemSection />
      <DirectionsSection />
      <ReliabilitySection />
      <ProductSection />
      <CapabilitiesSection />
      <SecuritySection />
      <StartSection />
      <PricingSection />
      <FaqSection />
      <ClosingSection />
    </>
  );
}
