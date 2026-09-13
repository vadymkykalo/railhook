import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import '../../i18n';
import i18n from '../../i18n';
import RetryJitterNote from '../RetryJitterNote';

/**
 * QA saw a retry arrive after 32 seconds on a ladder that said 60 and filed it as a bug. It is
 * the full jitter RetryLadder applies on purpose — every place a ladder is drawn now says so.
 */
describe('RetryJitterNote', () => {
  it('states the jitter the worker actually applies', () => {
    const ladder = readFileSync(resolve(__dirname,
      '../../../../railhook-common/src/main/java/com/webhook/platform/common/retry/RetryLadder.java'), 'utf8');
    expect(ladder).toMatch(/0\.5/);
    expect(ladder).toMatch(/1\.5|\b1\.0\b/);

    render(<RetryJitterNote />);
    expect(screen.getByText(/50% and 150%/)).toBeInTheDocument();
  });

  it('in Ukrainian too', async () => {
    await i18n.changeLanguage('uk');
    try {
      render(<RetryJitterNote />);
      expect(screen.getByText(/50% до 150%/)).toBeInTheDocument();
    } finally {
      await i18n.changeLanguage('en');
    }
  });

  it('sits under every retry ladder the dashboard draws', () => {
    for (const file of ['components/CreateSubscriptionModal.tsx', 'pages/ConnectionSetupPage.tsx', 'pages/IncomingSourceDetailPage.tsx']) {
      expect(readFileSync(resolve(__dirname, '../..', file), 'utf8'), file).toContain('<RetryJitterNote');
    }
  });
});
