import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { IMPOSSIBLE_TLDS, hasImpossibleTld, suggestEmail } from '../emailTypos';

describe('the refused endings', () => {
  it('are the same list the API refuses', () => {
    const java = readFileSync(resolve(__dirname,
      '../../../../railhook-api/src/main/java/com/webhook/platform/api/dto/validation/EmailTypoPolicy.java'), 'utf8');
    const entries = Object.fromEntries(
      [...java.matchAll(/Map\.entry\("([a-z]+)", "([a-z]+)"\)/g)].map((m) => [m[1], m[2]]),
    );
    expect(entries).toEqual(IMPOSSIBLE_TLDS);
  });
});

describe('suggestEmail', () => {
  it.each([
    ['wheelet1228@gmail.con', 'wheelet1228@gmail.com'],
    ['a@gmial.com', 'a@gmail.com'],
    ['a@gamil.com', 'a@gmail.com'],
    ['a@gmail.cmo', 'a@gmail.com'],
    ['a@gmail.comm', 'a@gmail.com'],
    ['a@hotmial.com', 'a@hotmail.com'],
    ['a@yahooo.com', 'a@yahoo.com'],
    ['a@outlok.com', 'a@outlook.com'],
    ['a@ukr.nt', 'a@ukr.net'],
    ['a@icloud.co', 'a@icloud.com'],
    ['a@acme.con', 'a@acme.com'],
    ['a@company.nte', 'a@company.net'],
  ])('suggests %s → %s', (typed, expected) => {
    expect(suggestEmail(typed)).toBe(expected);
  });

  it('keeps the part before @ exactly as typed', () => {
    expect(suggestEmail('Wheelet.1228+qa@GMAIL.CON')).toBe('Wheelet.1228+qa@gmail.com');
  });

  it.each([
    'person@gmail.com',
    'person@mail.com',
    'person@gmx.de',
    'person@ukr.net',
    'person@i.ua',
    'person@acme.io',
    'person@example.co.uk',
    'person@railhook.dev',
    'not an email',
    '',
  ])('leaves %s alone', (typed) => {
    expect(suggestEmail(typed)).toBeNull();
  });
});

describe('hasImpossibleTld', () => {
  it.each(['a@gmail.con', 'a@x.cmo', 'a@x.comm', 'a@ukr.nt', 'a@x.ogr'])('%s cannot receive mail', (email) => {
    expect(hasImpossibleTld(email)).toBe(true);
  });

  // A typo of a popular domain is a suggestion, never a refusal: gmial.com is registrable.
  it.each(['a@gmial.com', 'a@icloud.co', 'a@acme.io', 'a@x.co', 'a@x.om', 'a@x.cm', 'nope'])('%s is allowed', (email) => {
    expect(hasImpossibleTld(email)).toBe(false);
  });
});
