import React, { Suspense } from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import BootSplash from './components/BootSplash';
import './i18n';
import { initCSP } from './lib/csp';
import { initTheme } from './lib/theme';
import { installStaleChunkReload } from './lib/staleChunkReload';
import './index.css';

initCSP();
initTheme();
installStaleChunkReload();

// Locale bundles load lazily, so useTranslation() suspends on first load and on every language switch.
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <Suspense fallback={<BootSplash />}>
      <App />
    </Suspense>
  </React.StrictMode>
);
