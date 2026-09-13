import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Input } from '../ui/input';
import { Textarea } from '../ui/textarea';

/**
 * iOS Safari zooms the page into any focused field whose text is smaller than 16px, and leaves it
 * zoomed. At 14px the registration form on a phone ended up cut off at the right edge the moment
 * someone tapped a field. Form fields are 16px below the `sm` breakpoint and 14px from it.
 */
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
