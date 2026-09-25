import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Input } from '../ui/input';
import { Textarea } from '../ui/textarea';

/** iOS Safari zooms into any focused field under 16px and stays zoomed. */
describe('form fields are 16px on phones', () => {
  it('Input', () => {
    render(<Input aria-label="field" />);
    const cls = screen.getByLabelText('field').className;
    expect(cls).toMatch(/(^|\s)text-base(\s|$)/);
    expect(cls).toMatch(/(^|\s)sm:text-sm(\s|$)/);
  });

  it('Textarea', () => {
    render(<Textarea aria-label="area" />);
    const cls = screen.getByLabelText('area').className;
    expect(cls).toMatch(/(^|\s)text-base(\s|$)/);
    expect(cls).toMatch(/(^|\s)sm:text-sm(\s|$)/);
  });
});
