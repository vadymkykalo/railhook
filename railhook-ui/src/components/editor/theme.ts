import { EditorView } from '@codemirror/view';
import { HighlightStyle } from '@codemirror/language';
import { tags } from '@lezer/highlight';

/**
 * One editor skin, shared by the JSON surface and the script surface.
 *
 * It lived inside `JsonEditor` until there was a second editor, and copying it
 * would have meant two editors drifting apart in a product whose whole point is
 * that the preview and the delivery do not. Every colour is a design token read
 * through a CSS custom property, so CodeMirror repaints with the app for free.
 *
 * The syntax palette is deliberately two-tone. The four status hues are reserved
 * for statuses, so a JSON string is not allowed to be "ok green"; keys and
 * keywords carry the brand accent, values carry ink, and the rest is separated
 * by weight and italics rather than by inventing colours.
 */
export const tokenHighlight = HighlightStyle.define([
  { tag: tags.propertyName, color: 'hsl(var(--primary))', fontWeight: '500' },
  { tag: tags.string, color: 'hsl(var(--foreground))' },
  { tag: tags.number, color: 'hsl(var(--foreground))', fontVariantNumeric: 'tabular-nums' },
  { tag: tags.bool, color: 'hsl(var(--muted-foreground))', fontStyle: 'italic' },
  { tag: tags.null, color: 'hsl(var(--muted-foreground))', fontStyle: 'italic' },
  { tag: tags.punctuation, color: 'hsl(var(--muted-foreground))' },
  { tag: tags.separator, color: 'hsl(var(--muted-foreground))' },
  { tag: tags.brace, color: 'hsl(var(--muted-foreground))' },
  { tag: tags.squareBracket, color: 'hsl(var(--muted-foreground))' },
  { tag: tags.invalid, color: 'hsl(var(--halt))' },

  // JavaScript only. Same two-tone rule: structure in accent, content in ink,
  // everything else demoted rather than given a hue of its own.
  { tag: tags.keyword, color: 'hsl(var(--primary))', fontWeight: '500' },
  { tag: tags.controlKeyword, color: 'hsl(var(--primary))', fontWeight: '500' },
  { tag: tags.definitionKeyword, color: 'hsl(var(--primary))', fontWeight: '500' },
  { tag: tags.moduleKeyword, color: 'hsl(var(--primary))', fontWeight: '500' },
  { tag: tags.operatorKeyword, color: 'hsl(var(--primary))' },
  { tag: tags.function(tags.variableName), color: 'hsl(var(--foreground))', fontWeight: '500' },
  { tag: tags.function(tags.definition(tags.variableName)), color: 'hsl(var(--foreground))', fontWeight: '600' },
  { tag: tags.variableName, color: 'hsl(var(--foreground))' },
  { tag: tags.definition(tags.variableName), color: 'hsl(var(--foreground))' },
  { tag: tags.operator, color: 'hsl(var(--muted-foreground))' },
  { tag: tags.comment, color: 'hsl(var(--muted-foreground))', fontStyle: 'italic' },
  { tag: tags.lineComment, color: 'hsl(var(--muted-foreground))', fontStyle: 'italic' },
  { tag: tags.blockComment, color: 'hsl(var(--muted-foreground))', fontStyle: 'italic' },
  { tag: tags.regexp, color: 'hsl(var(--foreground))' },
  { tag: tags.self, color: 'hsl(var(--primary))', fontStyle: 'italic' },
]);

export interface EditorSkin {
  minHeight: string;
  maxHeight: string;
  readOnly: boolean;
  isDark: boolean;
}

/** The chrome: sizing, gutters, caret, selection, focus ring — all in tokens. */
export function editorTheme({ minHeight, maxHeight, readOnly, isDark }: EditorSkin) {
  return EditorView.theme(
    {
      '&': {
        minHeight,
        maxHeight,
        fontSize: '12px',
        color: 'hsl(var(--foreground))',
        border: '1px solid hsl(var(--rail))',
        borderRadius: 'calc(var(--radius) - 2px)',
        backgroundColor: readOnly ? 'hsl(var(--muted) / 0.4)' : 'hsl(var(--card))',
      },
      '.cm-scroller': {
        overflow: 'auto',
        maxHeight,
        fontFamily: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, "Liberation Mono", monospace',
        lineHeight: '1.6',
      },
      '.cm-content': {
        padding: '8px 0',
        caretColor: 'hsl(var(--foreground))',
      },
      '.cm-cursor, .cm-dropCursor': {
        borderLeftColor: 'hsl(var(--foreground))',
      },
      '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, .cm-content ::selection': {
        backgroundColor: 'hsl(var(--accent))',
      },
      '.cm-gutters': {
        backgroundColor: 'hsl(var(--muted) / 0.6)',
        color: 'hsl(var(--muted-foreground))',
        border: 'none',
        borderRight: '1px solid hsl(var(--rail))',
      },
      '.cm-activeLine': {
        backgroundColor: 'hsl(var(--accent) / 0.35)',
      },
      '.cm-activeLineGutter': {
        backgroundColor: 'hsl(var(--accent) / 0.35)',
        color: 'hsl(var(--foreground))',
      },
      '.cm-foldPlaceholder': {
        backgroundColor: 'hsl(var(--secondary))',
        color: 'hsl(var(--muted-foreground))',
        border: '1px solid hsl(var(--rail))',
      },
      '&.cm-focused': {
        outline: '2px solid hsl(var(--ring))',
        outlineOffset: '-1px',
      },
      '.cm-matchingBracket, &.cm-focused .cm-matchingBracket': {
        backgroundColor: 'hsl(var(--accent))',
        color: 'inherit',
        outline: 'none',
      },
      '.cm-placeholder': {
        color: 'hsl(var(--muted-foreground))',
        fontStyle: 'italic',
      },
      // The failure line. A script that throws names a line, and that line is the
      // one thing the author needs to find — so it is marked in the document, not
      // only described in a panel that has scrolled away.
      '.cm-lintRange-error': {
        backgroundImage: 'none',
        backgroundColor: 'hsl(var(--halt) / 0.18)',
        borderBottom: '2px solid hsl(var(--halt))',
      },
      '.cm-tooltip': {
        backgroundColor: 'hsl(var(--popover))',
        color: 'hsl(var(--popover-foreground))',
        border: '1px solid hsl(var(--rail))',
        borderRadius: 'calc(var(--radius) - 2px)',
        boxShadow: '0 8px 24px hsl(var(--foreground) / 0.12)',
      },
      '.cm-tooltip-autocomplete > ul > li[aria-selected]': {
        backgroundColor: 'hsl(var(--accent))',
        color: 'hsl(var(--accent-foreground))',
      },
      '.cm-tooltip.cm-tooltip-autocomplete > ul > li': {
        fontFamily: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
        padding: '2px 8px',
      },
      '.cm-completionDetail': {
        color: 'hsl(var(--muted-foreground))',
        fontStyle: 'normal',
        marginLeft: '8px',
      },
    },
    { dark: isDark },
  );
}
