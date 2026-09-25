/// <reference types="vitest" />
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import pkg from './package.json' with { type: 'json' }
import { parseFrontMatter } from './src/lib/frontMatter'
import { renderFeed, type FeedItem } from './src/lib/blogFeed'

/** A file, not app-rendered: a feed must be servable by nginx without JavaScript. */
function blogRss(): Plugin {
  const SITE = 'https://site-url.railhook.invalid'
  const dir = fileURLToPath(new URL('./src/content/blog', import.meta.url))

  function feed(): string {
    if (!existsSync(dir)) return renderFeed(SITE, [])
    const items = readdirSync(dir, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && existsSync(join(dir, entry.name, 'en.md')))
      .map((entry) => ({
        slug: entry.name,
        ...parseFrontMatter(readFileSync(join(dir, entry.name, 'en.md'), 'utf8')).values,
      }))
      .filter((item): item is FeedItem => Boolean(item.title && item.date && item.description))
      .sort((a, b) => b.date.localeCompare(a.date) || a.slug.localeCompare(b.slug))
    return renderFeed(SITE, items)
  }

  return {
    name: 'railhook-blog-rss',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0] !== '/blog/rss.xml') return next()
        res.setHeader('Content-Type', 'application/rss+xml; charset=utf-8')
        res.end(feed())
      })
    },
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: 'blog/rss.xml', source: feed() })
    },
  }
}

export default defineConfig({
  plugins: [react(), blogRss()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    // Dev has no nginx, so without this proxy every API call 404s on Vite itself.
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
      // Keeps /docs/ links working under npm run dev (make docs-dev serves it on 4321).
      '/docs': {
        target: 'http://localhost:4321',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: false,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov', 'json-summary'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.d.ts',
        'src/**/*.{test,spec}.{ts,tsx}',
        'src/test/**',
        'src/main.tsx',
        'src/vite-env.d.ts',
        'src/i18n/locales/**',
        'src/types/**',
      ],
      // Set a few points under the coverage-v8 4.x measurement; ratchet up as UI tests land.
      thresholds: {
        lines: 38,
        statements: 36,
        functions: 30,
        branches: 32,
      },
    },
  }
})
