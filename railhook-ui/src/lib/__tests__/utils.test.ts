import { describe, it, expect } from 'vitest';
import { cn } from '../utils';

describe('cn (class name merger)', () => {
  it('merges simple class names', () => {
    expect(cn('foo', 'bar')).toBe('foo bar');
  });

  it('handles conditional classes', () => {
    expect(cn('base', false && 'hidden', 'visible')).toBe('base visible');
  });

  it('deduplicates tailwind classes (last wins)', () => {
    expect(cn('p-4', 'p-2')).toBe('p-2');
  });

  it('merges tailwind conflicting utilities', () => {
    expect(cn('text-red-500', 'text-blue-500')).toBe('text-blue-500');
  });

  it('handles empty inputs', () => {
    expect(cn()).toBe('');
    expect(cn('')).toBe('');
    expect(cn(undefined)).toBe('');
  });

  it('handles array inputs via clsx', () => {
    expect(cn(['foo', 'bar'])).toBe('foo bar');
  });
});
