import i18n from 'i18next';
import type { BackendModule, ReadCallback } from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

// One dynamic import per language, so the initial bundle ships only the active locale.
const localeLoaders: Record<string, () => Promise<{ default: Record<string, unknown> }>> = {
  en: () => import('./locales/en.json'),
  uk: () => import('./locales/uk.json'),
};

const dynamicImportBackend: BackendModule = {
  type: 'backend',
  init() {
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
      // useTranslation() suspends until the locale loads; main.tsx wraps the app in <Suspense>.
      useSuspense: true,
    },
  });

export default i18n;
