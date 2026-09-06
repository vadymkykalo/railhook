import { useEffect, useRef, useCallback, useState } from 'react';
import { EditorView, keymap, placeholder as cmPlaceholder, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { json } from '@codemirror/lang-json';
import { defaultKeymap, indentWithTab } from '@codemirror/commands';
import { syntaxHighlighting, HighlightStyle, bracketMatching, foldGutter } from '@codemirror/language';
import { tags } from '@lezer/highlight';
import { lintGutter } from '@codemirror/lint';
import { closeBrackets } from '@codemirror/autocomplete';

/**
 * The JSON surface the whole workbench is written on.
 *
 * It used to load `@codemirror/theme-one-dark` and paint a fixed slate
 * rectangle whichever theme the app was in — a dark hole on a paper-white
 * page — and it only ever sampled the theme once, at mount, so toggling the
 * app theme left the editor behind until the page remounted. Both are fixed
 * here: every colour is a design token read through CSS custom properties, so
 * the editor repaints with the app for free, and `useIsDarkTheme` watches the
 * root element's class so CodeMirror's own dark-mode behaviour (selection,
 * caret, matching brackets) flips at the same moment.
 *
 * The syntax palette is deliberately two-tone. The four status hues are
 * reserved for statuses, so a JSON string is not allowed to be "ok green";
 * keys carry the brand teal, values carry ink, and the rest is separated by
 * weight and italics rather than by inventing colours.
 */

/** True while the app is in dark mode, and re-rendered when that changes. */
export function useIsDarkTheme(): boolean {
  const [isDark, setIsDark] = useState(
    () => typeof document !== 'undefined' && document.documentElement.classList.contains('dark'),
  );

  useEffect(() => {
    if (typeof document === 'undefined') return;
    const root = document.documentElement;
    const sync = () => setIsDark(root.classList.contains('dark'));
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(root, { attributes: true, attributeFilter: ['class'] });
    return () => observer.disconnect();
  }, []);

  return isDark;
}

/**
 * CodeMirror injects these rules into a real stylesheet, so `hsl(var(--token))`
 * resolves against the live token set — one highlight style serves both themes.
 */
const tokenHighlight = HighlightStyle.define([
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
]);

interface JsonEditorProps {
  value: string;
  onChange?: (value: string) => void;
  placeholder?: string;
  readOnly?: boolean;
  minHeight?: string;
  maxHeight?: string;
  className?: string;
  /** Forces a theme; omit to follow the app. */
  darkMode?: boolean;
}

export default function JsonEditor({
  value,
  onChange,
  placeholder = '',
  readOnly = false,
  minHeight = '200px',
  maxHeight = '400px',
  className = '',
  darkMode,
}: JsonEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  const appIsDark = useIsDarkTheme();
  const isDark = darkMode ?? appIsDark;

  const createState = useCallback((doc: string) => {
    const extensions = [
      json(),
      lineNumbers(),
      highlightActiveLine(),
      highlightActiveLineGutter(),
      bracketMatching(),
      closeBrackets(),
      foldGutter(),
      lintGutter(),
      syntaxHighlighting(tokenHighlight, { fallback: true }),
      keymap.of([...defaultKeymap, indentWithTab]),
      EditorView.lineWrapping,
      EditorView.theme(
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
        },
        { dark: isDark },
      ),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          onChangeRef.current?.(update.state.doc.toString());
        }
      }),
    ];

    if (placeholder) {
      extensions.push(cmPlaceholder(placeholder));
    }

    if (readOnly) {
      extensions.push(EditorState.readOnly.of(true));
    }

    return EditorState.create({ doc, extensions });
  }, [minHeight, maxHeight, placeholder, readOnly, isDark]);

  // Initialize editor
  useEffect(() => {
    if (!containerRef.current) return;

    const view = new EditorView({
      state: createState(value),
      parent: containerRef.current,
    });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // Only run on mount
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Sync external value changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentDoc = view.state.doc.toString();
    if (currentDoc !== value) {
      view.dispatch({
        changes: { from: 0, to: currentDoc.length, insert: value },
      });
    }
  }, [value]);

  // Recreate state when theme/readOnly changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentDoc = view.state.doc.toString();
    view.setState(createState(currentDoc));
  }, [isDark, readOnly, createState]);

  return (
    <div ref={containerRef} className={`json-editor ${className}`} />
  );
}
