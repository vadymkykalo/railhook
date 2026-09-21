import { useEffect, useRef, useCallback } from 'react';
import { useIsDarkTheme } from '../hooks/useIsDarkTheme';
import { EditorView, keymap, placeholder as cmPlaceholder, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view';
import { EditorState } from '@codemirror/state';
import { json } from '@codemirror/lang-json';
import { defaultKeymap, indentWithTab } from '@codemirror/commands';
import { syntaxHighlighting, bracketMatching, foldGutter } from '@codemirror/language';
import { lintGutter } from '@codemirror/lint';
import { closeBrackets } from '@codemirror/autocomplete';
import { editorTheme, tokenHighlight } from './editor/theme';

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
 * The skin itself now lives in `editor/theme.ts`, shared with the script
 * editor. Two editors in the same workbench drifting apart would be the same
 * bug this product exists to prevent, one layer up.
 */

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
  'aria-label'?: string;
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
  'aria-label': ariaLabel,
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
      editorTheme({ minHeight, maxHeight, readOnly, isDark }),
      EditorView.contentAttributes.of(ariaLabel ? { 'aria-label': ariaLabel } : {}),
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
  }, [minHeight, maxHeight, placeholder, readOnly, isDark, ariaLabel]);

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
