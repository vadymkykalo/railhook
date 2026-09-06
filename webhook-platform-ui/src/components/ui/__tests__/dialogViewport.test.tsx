import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '../dialog';
import { AlertDialog, AlertDialogContent, AlertDialogTitle } from '../alert-dialog';
import '../../../i18n';

/**
 * A dialog may not grow past the window it is centred in.
 *
 * <p>This is a real report, not a hypothetical: the endpoint form opened on a laptop with its
 * title above the top of the screen and Save and Cancel below the bottom, and because Radix
 * freezes the page behind an open dialog there was nothing to scroll and no way to reach either.
 *
 * <p>What makes it worth a test rather than a fix is how it survived: six call sites had already
 * pasted `max-h-[85vh] overflow-y-auto` onto their own dialog, at three different heights, so
 * the bug looked fixed everywhere anyone had looked. The last assertion here is about that — the
 * cap belongs to the primitive, and a call site adding its own is the pattern coming back.
 */

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
    // 100vh counts the space behind a mobile address bar, so a vh-capped dialog is still
    // taller than the screen on exactly the devices with the least room to spare.
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
    // The six that did were the reason the other dozen went unnoticed. One cap, one place.
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
