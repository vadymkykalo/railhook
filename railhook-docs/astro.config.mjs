// @ts-check
import { existsSync } from 'node:fs';
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import starlightLinksValidator from 'starlight-links-validator';
import sitemap from '@astrojs/sitemap';

import { API_REFERENCE, SIDEBAR_GROUPS } from './src/data/slugs.mjs';
import { lastmodFor, socialImageHead } from './scripts/seo.mjs';

const FONTS =
  'https://fonts.googleapis.com/css2?family=Geist:wght@400;500&family=JetBrains+Mono:wght@400;500&display=swap';

const DOCS_DIR = new URL('./src/content/docs/', import.meta.url);

// Starlight fails the build on a sidebar slug with no page, so only written pages are listed.
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
  // Placeholder: one image serves every deployment, nginx rewrites it to RAILHOOK_SITE_URL.
  site: 'https://site-url.railhook.invalid',
  base: '/docs',
  trailingSlash: 'always',
  integrations: [
    starlight({
      title: 'Railhook Docs',
      logo: { light: './src/assets/railhook-mark.svg', dark: './src/assets/railhook-mark-dark.svg', alt: 'Railhook' },
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
      components: {
        Header: './src/components/Header.astro',
        ThemeSelect: './src/components/ThemeToggle.astro',
        LanguageSelect: './src/components/LanguageSwitch.astro',
      },
      head: [
        { tag: 'link', attrs: { rel: 'preconnect', href: 'https://fonts.googleapis.com' } },
        { tag: 'link', attrs: { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' } },
        { tag: 'link', attrs: { rel: 'stylesheet', href: FONTS } },
        ...socialImageHead,
        // analytics.js adds the Cloudflare beacon only when config.js carries a token.
        { tag: 'script', attrs: { src: '/config.js' } },
        { tag: 'script', attrs: { src: '/analytics.js', defer: true } },
      ],
      // Code is near-black in both site themes, so one code theme and no switch.
      expressiveCode: {
        themes: ['github-dark'],
        useStarlightDarkModeSwitch: false,
        useStarlightUiThemeColors: false,
        styleOverrides: {
          borderRadius: '0',
          borderColor: '#2E2E2E',
          codeBackground: '#111111',
          codeForeground: '#EDEDED',
          codeFontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
          uiFontFamily: "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, monospace",
          uiFontSize: '0.75rem',
          frames: {
            frameBoxShadowCssValue: 'none',
            editorBackground: '#111111',
            editorTabBarBackground: '#111111',
            editorTabBarBorderBottomColor: '#2E2E2E',
            editorActiveTabBackground: '#111111',
            editorActiveTabForeground: '#EDEDED',
            editorActiveTabIndicatorBottomColor: '#7FE7FF',
            // github-dark paints this red-orange, a status hue.
            editorActiveTabIndicatorTopColor: 'transparent',
            terminalBackground: '#111111',
            terminalTitlebarBackground: '#111111',
            terminalTitlebarForeground: '#9C9C9C',
            terminalTitlebarBorderBottomColor: '#2E2E2E',
            terminalTitlebarDotsForeground: '#474747',
            terminalTitlebarDotsOpacity: '1',
            inlineButtonForeground: '#EDEDED',
            inlineButtonBorder: '#2E2E2E',
            tooltipSuccessBackground: '#7FE7FF',
            tooltipSuccessForeground: '#000000',
          },
        },
      },
      // The validator cannot see the custom Scalar page, so links to it would be false reports.
      plugins: [starlightLinksValidator({ exclude: ['/docs/api-reference/', '/docs/uk/api-reference/', '/api-reference/', '/uk/api-reference/'] })],
    }),
    // Declared by hand (Starlight otherwise adds its own) only to add lastmod.
    sitemap({
      i18n: { defaultLocale: 'root', locales: { root: 'en', uk: 'uk' } },
      serialize: (item) => ({ ...item, lastmod: lastmodFor(item.url).toISOString() }),
    }),
  ],
});
