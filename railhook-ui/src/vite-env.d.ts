/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_URL?: string
  readonly VITE_CSP_EXTRA_CONNECT?: string
  /** Public origin this build is served from. Unset = derive from the browser. */
  readonly VITE_SITE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/** The dashboard build, injected by vite.config.ts from package.json. */
declare const __APP_VERSION__: string
