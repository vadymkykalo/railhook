module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  roots: ['<rootDir>/src'],
  testMatch: ['**/__tests__/**/*.test.ts'],
  // Lets the smoke test `import ... from '@railhook/node'` resolve to this
  // package's own source without a real install — mirrors what a consumer
  // gets from node_modules after `npm install @railhook/node`.
  moduleNameMapper: {
    '^@railhook/node$': '<rootDir>/src/index.ts',
  },
  collectCoverageFrom: [
    'src/**/*.ts',
    '!src/**/__tests__/**',
    '!src/index.ts',
  ],
  coverageThreshold: {
    global: {
      branches: 80,
      functions: 80,
      lines: 80,
      statements: 80,
    },
  },
};
