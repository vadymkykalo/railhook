import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import HeroSection from './landing/HeroSection';
import DirectionsSection from './landing/DirectionsSection';
import ReliabilitySection from './landing/ReliabilitySection';
import ArchitectureSection from './landing/ArchitectureSection';
import ProductSection from './landing/ProductSection';
import RunSection from './landing/RunSection';
import DeveloperSection from './landing/DeveloperSection';
import { useDocumentMeta } from '../hooks/useDocumentMeta';

/**
 * Seven sections, read by people who decide on outcomes as much as by the engineers who install:
 * the promise and both ways in (hero), what it does in either direction (directions), what
 * happens when the other side is down (reliability), what it is built on and why that keeps
 * events safe (architecture), what the team will look at (product), how
 * to run it — free in the cloud or on your own servers (run), and where an engineer starts, with
 * the ask again (developer).
 *
 * The page it replaced had twelve sections, a pricing grid for plans that did not exist yet and
 * a mechanism on every screen. How retries, signatures and ordering work is in the docs; the
 * page stays under 600 words, and a test holds it there.
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
      <DirectionsSection />
      <ReliabilitySection />
      <ArchitectureSection />
      <ProductSection />
      <RunSection />
      <DeveloperSection />
    </>
  );
}
