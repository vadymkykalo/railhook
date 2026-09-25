import { useCallback, useEffect, useRef } from 'react';
import {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  placeholder as cmPlaceholder,
} from '@codemirror/view';
import { EditorState, Prec } from '@codemirror/state';
import { javascript, javascriptLanguage } from '@codemirror/lang-javascript';
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands';
import { syntaxHighlighting, bracketMatching, foldGutter, indentOnInput } from '@codemirror/language';
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint';
import {
  autocompletion, closeBrackets, closeBracketsKeymap, completionKeymap,
  type CompletionContext, type CompletionResult,
} from '@codemirror/autocomplete';
import { useIsDarkTheme } from '../../hooks/useIsDarkTheme';
import { editorTheme, tokenHighlight } from './theme';

/** CodeMirror, not Monaco: Monaco needs blob workers, which the CSP (no worker-src, no unsafe-eval) forbids. */

const WEBHOOK_MEMBERS: Array<{ label: string; detail: string; info: string }> = [
  { label: 'payload', detail: 'object', info: 'The event body, already parsed.' },
  { label: 'eventType', detail: 'string', info: 'The event type, e.g. "order.completed".' },
  { label: 'eventId', detail: 'string', info: "The event's id." },
  { label: 'timestamp', detail: 'string', info: 'When the event was announced, ISO-8601.' },
  { label: 'direction', detail: '"OUTGOING" | "INCOMING"', info: 'Which way this one is travelling.' },
  { label: 'url', detail: 'string', info: 'The destination URL. Read-only: a transformation cannot redirect a delivery.' },
  { label: 'headers', detail: 'object', info: 'The request headers computed so far.' },
];

const RETURN_MEMBERS: Array<{ label: string; detail: string; info: string }> = [
  { label: 'payload', detail: 'any', info: 'The body to send. Required unless cancel is true.' },
  { label: 'headers', detail: 'object', info: 'Headers to add, over Railhook’s own and under the endpoint’s.' },
  { label: 'cancel', detail: 'boolean', info: 'True drops the delivery. Nothing is sent and nothing is retried.' },
  { label: 'cancelReason', detail: 'string', info: 'Why, recorded on the delivery.' },
];

const HANDLER_SNIPPET = `function handler(webhook) {
  return { payload: webhook.payload };
}`;

function contractCompletions(context: CompletionContext): CompletionResult | null {
  const afterWebhook = context.matchBefore(/webhook\.\w*/);
  if (afterWebhook) {
    return {
      from: afterWebhook.from + 'webhook.'.length,
      options: WEBHOOK_MEMBERS.map((member) => ({
        label: member.label, type: 'property', detail: member.detail, info: member.info,
      })),
    };
  }

  const inReturn = context.matchBefore(/return\s*\{\s*\w*/);
  if (inReturn) {
    const word = context.matchBefore(/\w*/);
    return {
      from: word ? word.from : context.pos,
      options: RETURN_MEMBERS.map((member) => ({
        label: member.label, type: 'property', detail: member.detail, info: member.info,
      })),
    };
  }

  const word = context.matchBefore(/\w+/);
  if (!word || (word.from === word.to && !context.explicit)) {
    return null;
  }
  return {
    from: word.from,
    options: [
      { label: 'handler', type: 'function', detail: 'the entry point', apply: HANDLER_SNIPPET,
        info: 'Every transformation is one handler(webhook).' },
      { label: 'webhook', type: 'variable', detail: 'the event and the delivery context' },
      { label: 'console', type: 'variable', detail: 'log · info · warn · error · debug',
        info: 'Captured and shown in the Console tab. It does not reach your endpoint.' },
    ],
  };
}

interface ScriptEditorProps {
  value: string;
  onChange?: (value: string) => void;
  onRun?: () => void;
  onSave?: () => void;
  placeholder?: string;
  readOnly?: boolean;
  minHeight?: string;
  maxHeight?: string;
  className?: string;
  errorLine?: number | null;
  errorMessage?: string | null;
  'aria-label'?: string;
}

export default function ScriptEditor({
  value,
  onChange,
  onRun,
  onSave,
  placeholder = '',
  readOnly = false,
  minHeight = '320px',
  maxHeight = '560px',
  className = '',
  errorLine = null,
  errorMessage = null,
  'aria-label': ariaLabel,
}: ScriptEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  const onRunRef = useRef(onRun);
  const onSaveRef = useRef(onSave);
  onChangeRef.current = onChange;
  onRunRef.current = onRun;
  onSaveRef.current = onSave;

  const isDark = useIsDarkTheme();

  const createState = useCallback((doc: string) => {
    const extensions = [
      javascript(),
      javascriptLanguage.data.of({ autocomplete: contractCompletions }),
      lineNumbers(),
      history(),
      highlightActiveLine(),
      highlightActiveLineGutter(),
      bracketMatching(),
      closeBrackets(),
      indentOnInput(),
      foldGutter(),
      lintGutter(),
      autocompletion({ override: [contractCompletions] }),
      syntaxHighlighting(tokenHighlight, { fallback: true }),
      // Above the default keymap, which binds Enter.
      Prec.highest(keymap.of([
        {
          key: 'Mod-Enter',
          preventDefault: true,
          run: () => { onRunRef.current?.(); return true; },
        },
        {
          key: 'Mod-s',
          preventDefault: true,
          run: () => { onSaveRef.current?.(); return true; },
        },
      ])),
      keymap.of([...closeBracketsKeymap, ...completionKeymap,
        ...historyKeymap, ...defaultKeymap, indentWithTab]),
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

  useEffect(() => {
    if (!containerRef.current) return;
    const view = new EditorView({ state: createState(value), parent: containerRef.current });
    viewRef.current = view;
    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- mount only; effects below sync doc and theme
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentDoc = view.state.doc.toString();
    if (currentDoc !== value) {
      view.dispatch({ changes: { from: 0, to: currentDoc.length, insert: value } });
    }
  }, [value]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.setState(createState(view.state.doc.toString()));
  }, [isDark, readOnly, createState]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const diagnostics: Diagnostic[] = [];
    if (errorLine && errorLine >= 1 && errorLine <= view.state.doc.lines) {
      const line = view.state.doc.line(errorLine);
      diagnostics.push({
        from: line.from,
        to: line.to,
        severity: 'error',
        message: errorMessage || 'This line failed.',
      });
    }
    view.dispatch(setDiagnostics(view.state, diagnostics));
    // isDark: the theme effect rebuilds EditorState and drops the diagnostics.
  }, [errorLine, errorMessage, value, isDark]);

  return <div ref={containerRef} className={`script-editor ${className}`} />;
}
