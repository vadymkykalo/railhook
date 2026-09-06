module.exports = {
  root: true,
  env: { browser: true, es2020: true },
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
  ],
  ignorePatterns: ['dist', '.eslintrc.cjs', 'node_modules'],
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: {
      jsx: true,
    },
  },
  plugins: ['react', '@typescript-eslint', 'react-hooks', 'i18next'],
  settings: {
    react: {
      version: 'detect',
    },
  },
  rules: {
    'react/react-in-jsx-scope': 'off',
    'react/prop-types': 'off',
    'react/no-unescaped-entities': 'off',
    'react-hooks/exhaustive-deps': 'warn',
    '@typescript-eslint/no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
    '@typescript-eslint/no-explicit-any': 'off',
    // I18next is configured with interpolation.escapeValue: false (correct
    // for normal React rendering, since React already escapes text children).
    // That setting becomes a stored-XSS hole the moment translated, interpolated
    // text is piped into dangerouslySetInnerHTML instead — an unescaped
    // user-controlled value (project name, event type, email, …) gets injected
    // as raw HTML. Use react-i18next's <Trans> component for "bold a word in a
    // translated sentence" instead; it renders markup as real React elements and
    // escapes interpolated values.
    'react/no-danger': 'error',
  },
  overrides: [
    {
      // A bare render() builds its own QueryClient with retries ON, so an
      // error-state test sits through three exponential-backoff retries before
      // the assertion can run — it does not fail, it hangs, and the afternoon
      // goes into finding out why. renderPage() supplies a no-retry client, a
      // MemoryRouter for useParams and an authenticated OWNER for
      // usePermissions, which is also what the real app supplies.
      //
      // Scoped to page tests, not every test: the convention is about pages,
      // and a plain component test (components/__tests__/EmptyState.test.tsx)
      // has no route to render through — renderPage would need a path and an
      // initialEntry it does not have, so a blanket ban would be unfixable
      // there rather than merely inconvenient. src/test/renderPage.tsx itself
      // is outside src/pages and so is unaffected; it has to import the real
      // render.
      files: ['src/pages/**/*.test.tsx'],
      rules: {
        'no-restricted-imports': ['error', {
          paths: [{
            name: '@testing-library/react',
            importNames: ['render'],
            message: 'Use renderPage() from src/test/renderPage.tsx — a bare render() gets a retrying QueryClient, which hangs error-state tests.',
          }],
        }],
      },
    },
    {
      // These are the "core operational" pages an audit found leaking
      // raw hardcoded English JSX text instead of going through t() — status
      // filters, workflow node labels, rule action badges, etc. Scoped here
      // rather than project-wide because the rest of the app (Landing/Docs marketing
      // copy, code samples, other untouched pages) hasn't been audited for
      // this and would need its own pass of exclusions to avoid drowning in
      // false positives on legitimate literal content.
      //
      // Severity is 'warn', not 'error': eslint-plugin-i18next's jsx-text-only
      // mode still flags a handful of pre-existing, legitimately
      // non-translatable fragments in these files (JSONPath code samples like
      // `${'{'}$.id{'}'}`, "(v{version})" version annotations, "…" / "→"
      // decorative glyphs) that would take real per-case tuning to silence
      // without also silencing genuine future violations. `npm run lint`
      // doesn't pass --max-warnings, so this doesn't fail CI, but it does
      // surface in the lint step's output for every PR touching these files —
      // enough to catch a newly reintroduced raw string on review without
      // false-failing the build on the known pre-existing exceptions above.
      files: [
        'src/pages/DeliveriesPage.tsx',
        'src/pages/DeliveryDetailsSheet.tsx',
        'src/pages/RulesPage.tsx',
        'src/pages/IncomingSourceDetailPage.tsx',
        'src/pages/PiiRulesPage.tsx',
        'src/pages/TransformationsPage.tsx',
        'src/pages/EventDetailPage.tsx',
        'src/pages/WorkflowBuilderPage.tsx',
        'src/components/SendTestEventModal.tsx',
        'src/components/ErrorBoundary.tsx',
      ],
      plugins: ['i18next'],
      rules: {
        'i18next/no-literal-string': ['warn', {
          words: {
            exclude: [
              '[0-9!-/:-@[-`{-~]+',
              '[A-Z_-]+',
              '^(ms|s|req/s|v)$',
              '^\\(.*\\)$',
              '^[●•\\-–—→…]+$',
              '^[.$]',
            ],
          },
        }],
      },
    },
  ],
};
