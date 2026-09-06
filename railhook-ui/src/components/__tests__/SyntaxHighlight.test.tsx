import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import SyntaxHighlight, { highlight, normalizeLanguage } from '../SyntaxHighlight';
import { cliSamples, quickstartSamples, rulesSample, sdkSamples, signatureSamples } from '../../pages/docs/samples';

/**
 * The two things that can silently break here are a token that swallows the rest
 * of the file (an unterminated string alternative) and a scanner that stops
 * emitting text. Both show up as "the code no longer reads as the code", which is
 * why every assertion below is ultimately about lossless round-tripping.
 */

function plainText(code: string, language: Parameters<typeof highlight>[1]): string {
  render(
    <pre data-testid="out">
      <SyntaxHighlight code={code} language={language} />
    </pre>,
  );
  return screen.getByTestId('out').textContent ?? '';
}

describe('normalizeLanguage', () => {
  it('maps the labels the docs actually pass', () => {
    expect(normalizeLanguage('bash')).toBe('bash');
    expect(normalizeLanguage('curl')).toBe('bash');
    expect(normalizeLanguage('node')).toBe('javascript');
    expect(normalizeLanguage('typescript')).toBe('javascript');
    expect(normalizeLanguage('python')).toBe('python');
    expect(normalizeLanguage('php')).toBe('php');
    expect(normalizeLanguage('http')).toBe('http');
    expect(normalizeLanguage('json')).toBe('json');
  });

  it('falls back to unhighlighted text rather than guessing', () => {
    expect(normalizeLanguage('brainfuck')).toBe('text');
    expect(normalizeLanguage(undefined)).toBe('text');
  });
});

describe('highlight', () => {
  const cases: Array<[string, string, Parameters<typeof highlight>[1]]> = [
    ['a curl with a JSON body', rulesSample, 'bash'],
    ['a quickstart request', quickstartSamples.event, 'bash'],
    ['a shell session with comments', cliSamples.login, 'bash'],
    ['a piped install one-liner', cliSamples.install, 'bash'],
    ['a Node sample', signatureSamples.node, 'javascript'],
    ['a Python sample', signatureSamples.python, 'python'],
    ['a PHP sample', sdkSamples.php, 'php'],
  ];

  it.each(cases)('renders %s without losing a character', (_name, code, language) => {
    expect(plainText(code, language)).toBe(code);
  });

  it('reuses the same scanner across calls — a leaked lastIndex would drop the first token', () => {
    expect(highlight(rulesSample, 'bash')).toEqual(highlight(rulesSample, 'bash'));
  });

  it('highlights the JSON inside a shell-quoted body rather than flattening it', () => {
    render(
      <pre data-testid="out">
        <SyntaxHighlight code={`curl -d '{"name":"Production"}'`} language="bash" />
      </pre>,
    );
    const keys = screen.getByTestId('out').querySelectorAll('.text-primary');
    expect([...keys].map((node) => node.textContent)).toContain('"name"');
  });

  it('leaves unknown languages alone', () => {
    expect(highlight('anything at all', 'text')).toEqual(['anything at all']);
  });
});
