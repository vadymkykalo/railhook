import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '../dialog';
import { AlertDialog, AlertDialogContent, AlertDialogTitle } from '../alert-dialog';
import '../../../i18n';

/** Radix freezes the page behind a dialog, so a taller-than-window dialog hid Save with no way to scroll. */

const src = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');

function classesOf(el: Element | null): string {
  return el?.getAttribute('class') ?? '';
}

describe('a dialog is bounded by the viewport', () => {
  it('caps its height and scrolls inside itself', () => {
    render(
      <Dialog open>
        <DialogContent>
          <DialogHeader><DialogTitle>Add endpoint</DialogTitle></DialogHeader>
          <p>A form long enough to matter.</p>
          <DialogFooter><button type="button">Save</button></DialogFooter>
        </DialogContent>
      </Dialog>,
    );

    const content = classesOf(screen.getByRole('dialog'));
    expect(content).toMatch(/max-h-\[calc\(100dvh-2rem\)\]/);
    expect(content).toMatch(/overflow-y-auto/);
  });

  it('measures against dvh, which is the window a phone actually has', () => {
    // 100vh counts the space behind a mobile address bar.
    render(
      <Dialog open>
        <DialogContent><DialogTitle>Anything</DialogTitle></DialogContent>
      </Dialog>,
    );
    expect(classesOf(screen.getByRole('dialog'))).not.toMatch(/max-h-\[\d+vh\]/);
  });

  it('applies to the confirmation dialogs too', () => {
    render(
      <AlertDialog open>
        <AlertDialogContent><AlertDialogTitle>Delete this?</AlertDialogTitle></AlertDialogContent>
      </AlertDialog>,
    );

    const content = classesOf(screen.getByRole('dialog'));
    expect(content).toMatch(/max-h-\[calc\(100dvh-2rem\)\]/);
    expect(content).toMatch(/overflow-y-auto/);
  });

  it('lets a caller widen the dialog without losing the cap', () => {
    render(
      <Dialog open>
        <DialogContent className="max-w-4xl"><DialogTitle>Wide</DialogTitle></DialogContent>
      </Dialog>,
    );

    const content = classesOf(screen.getByRole('dialog'));
    expect(content).toMatch(/max-w-4xl/);
    expect(content).toMatch(/max-h-\[calc\(100dvh-2rem\)\]/);
  });

  it('has no call site setting a height of its own', () => {
    // The cap belongs to the primitive; a call site adding its own is the pattern coming back.
    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name);
        if (entry.isDirectory()) { walk(path); continue; }
        if (!entry.name.endsWith('.tsx')) continue;
        if (path.includes(`${'components'}/ui/`)) continue;
        for (const line of readFileSync(path, 'utf8').split('\n')) {
          if (/DialogContent[^>]*className="[^"]*max-h-/.test(line)) {
            offenders.push(`${path}: ${line.trim()}`);
          }
        }
      }
    };
    walk(src);
    expect(offenders).toEqual([]);
  });
});
