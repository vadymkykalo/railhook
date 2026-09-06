import i18n from 'i18next';
import type { BackendModule, ReadCallback } from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

// Locale JSON files are loaded on demand, one dynamic import() per language,
// so the initial bundle only ships the language actually needed instead of
// every supported locale (en ~160KB, uk ~230KB — previously both shipped in
// the main chunk regardless of which language rendered).
const localeLoaders: Record<string, () => Promise<{ default: Record<string, unknown> }>> = {
  en: () => import('./locales/en.json'),
  uk: () => import('./locales/uk.json'),
};

const dynamicImportBackend: BackendModule = {
  type: 'backend',
  init() {
    // No setup needed — each read() below resolves its own dynamic import().
  },
  read(language: string, _namespace: string, callback: ReadCallback) {
    const load = localeLoaders[language] ?? localeLoaders.en;
    load()
      .then((mod) => callback(null, mod.default))
      .catch((error) => callback(error, null));
  },
};

i18n
  .use(dynamicImportBackend)
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    fallbackLng: 'en',
    supportedLngs: ['en', 'uk'],
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ['localStorage', 'navigator'],
      lookupLocalStorage: 'i18n_lng',
      caches: ['localStorage'],
    },
    react: {
      // useTranslation() suspends (via React Suspense) until the active
      // language's bundle has been dynamically imported. The app is wrapped
      // in a top-level <Suspense> in main.tsx to catch this on first load and
      // on every language switch.
      useSuspense: true,
    },
  });

export default i18n;
