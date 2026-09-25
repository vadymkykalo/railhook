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
    // escapeValue is off, so t() output in dangerouslySetInnerHTML is stored XSS; use <Trans>.
    'react/no-danger': 'error',
  },
  overrides: [
    {
      // A bare render() retries queries, so error-state tests hang; use renderPage().
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
      // Warn, not error: a few literal fragments in these pages are legitimate.
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
