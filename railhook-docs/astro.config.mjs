// @ts-check
import { existsSync } from 'node:fs';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import sitemap from '@astrojs/sitemap';

import { API_REFERENCE, SIDEBAR_GROUPS } from './src/data/slugs.mjs';
import { lastmodFor, socialImageHead } from './scripts/seo.mjs';

/**
 * The docs are served from the UI image at /docs/, next to the dashboard, so every URL here
 * carries that base and a trailing slash — which is also how internal links are written.
 */

const FONTS =
  'https://fonts.googleapis.com/css2?family=Manrope:wght@600;700;800&family=Onest:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap';

const DOCS_DIR = new URL('./src/content/docs/', import.meta.url);

/**
 * The slug contract (src/data/slugs.mjs) lists pages that are still being written. Starlight
 * refuses to build with a sidebar slug that has no page, so the sidebar lists only the contract
 * slugs whose English page exists; a page appears in the sidebar the moment its file lands,
 * with no edit here. The parity check keeps the Ukrainian twin from lagging behind.
 */
const pageExists = (slug) => ['.mdx', '.md'].some((ext) => existsSync(new URL(`${slug}${ext}`, DOCS_DIR)));

const sidebar = [
  ...SIDEBAR_GROUPS.map((group) => ({
    label: group.label,
    translations: { uk: group.uk },
    items: group.slugs.filter(pageExists),
  })).filter((group) => group.items.length > 0),
  {
    label: 'Reference',
    translations: { uk: 'Довідник' },
    items: [{ label: API_REFERENCE.label, translations: { uk: API_REFERENCE.uk }, link: `/${API_REFERENCE.slug}/` }],
  },
];

export default defineConfig({
  // A placeholder origin, not an address: the image is built once for every deployment. nginx
  // rewrites it to the UI container's RAILHOOK_SITE_URL (or to nothing, a relative URL) when it
  // serves the pages and the sitemap.
  site: 'https://site-url.railhook.invalid',
  base: '/docs',
  trailingSlash: 'always',
  integrations: [
    starlight({
      title: 'Railhook Docs',
      logo: { src: './src/assets/railhook-mark.svg', alt: 'Railhook' },
      favicon: '/favicon.svg',
      defaultLocale: 'root',
      locales: {
        root: { label: 'English', lang: 'en' },
        uk: { label: 'Українська', lang: 'uk' },
      },
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/vadymkykalo/railhook' }],
      editLink: { baseUrl: 'https://github.com/vadymkykalo/railhook/edit/develop/railhook-docs/' },
      lastUpdated: true,
      sidebar,
      customCss: ['./src/styles/theme.css'],
      // The header's theme and language pickers, drawn like the dashboard's instead of as native selects.
      components: {
        ThemeSelect: './src/components/ThemeToggle.astro',
        LanguageSelect: './src/components/LanguageSwitch.astro',
      },
      head: [
        { tag: 'link', attrs: { rel: 'preconnect', href: 'https://fonts.googleapis.com' } },
        { tag: 'link', attrs: { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' } },
        { tag: 'link', attrs: { rel: 'stylesheet', href: FONTS } },
        ...socialImageHead,
      ],
      // The code surface is dark in both site themes, as it is on the landing page and in
      // the dashboard: one theme, no light/dark switch for code.
      expressiveCode: {
        themes: ['github-dark'],
        useStarlightDarkModeSwitch: false,
        useStarlightUiThemeColors: false,
        styleOverrides: {
          borderRadius: '12px',
          borderColor: '#23263A',
          codeBackground: '#0E1020',
          codeForeground: '#E6E8F2',
          codeFontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
          uiFontFamily: "'Onest', system-ui, -apple-system, 'Segoe UI', sans-serif",
          frames: {
            frameBoxShadowCssValue: 'none',
            editorBackground: '#0E1020',
            editorTabBarBackground: '#0E1020',
            editorTabBarBorderBottomColor: '#23263A',
            editorActiveTabBackground: '#0E1020',
            editorActiveTabForeground: '#E6E8F2',
            editorActiveTabIndicatorBottomColor: '#1D4BFF',
            // github-dark paints the top indicator in a red-orange — a status hue on chrome.
            editorActiveTabIndicatorTopColor: 'transparent',
            terminalBackground: '#0E1020',
            terminalTitlebarBackground: '#0E1020',
            terminalTitlebarForeground: '#8A90A6',
            terminalTitlebarBorderBottomColor: '#23263A',
            terminalTitlebarDotsForeground: '#8A90A6',
            inlineButtonForeground: '#E6E8F2',
            inlineButtonBorder: '#23263A',
          },
        },
      },
      // The API reference is a custom page (Scalar), which the validator cannot see into; the
      // pages themselves exist, so links to them are excluded rather than reported.
      plugins: [starlightLinksValidator({ exclude: ['/docs/api-reference/', '/docs/uk/api-reference/', '/api-reference/', '/uk/api-reference/'] })],
    }),
    // Starlight adds this integration itself unless one is already configured; declared here
    // with the same i18n alternates so every entry can also say when its page last changed.
    sitemap({
      i18n: { defaultLocale: 'root', locales: { root: 'en', uk: 'uk' } },
      serialize: (item) => ({ ...item, lastmod: lastmodFor(item.url).toISOString() }),
    }),
  ],
});
