import { useMemo, type ReactNode } from 'react';

/**
 * Read-only syntax highlighting for the code samples in the docs and on the
 * landing page.
 *
 * Why not CodeMirror, which is already a dependency? Because CodeMirror
 * highlights with Lezer, and Lezer is one prebuilt parser table per language.
 * `JsonEditor` pulls in exactly one of them — JSON — and its chunk is 336 kB.
 * The docs need bash, JSON, HTTP, JS/TS, Python and PHP; four more grammars
 * would cost several times the entire documentation route to colour samples
 * nobody edits. A scanner sized to short, static samples is the right shape for
 * text that is never parsed twice. Prism and highlight.js are the same trade at
 * a smaller discount, plus a second theming system to keep in step with tokens.
 *
 * Two constraints are load-bearing and are worth stating so nobody "optimises"
 * them away:
 *
 *   - The app ships `script-src 'self'` with no `unsafe-eval` (`src/lib/csp.ts`,
 *     and again in `nginx.conf`). Nothing here compiles code at runtime. The
 *     grammars below are regex *literals*, compiled when this module is parsed,
 *     and `lastIndex` is reset before each scan rather than the RegExp cloned,
 *     so not even a `new RegExp` runs. A highlighter that builds its matcher
 *     with `new Function` works perfectly in `vite dev` — which serves no CSP —
 *     and dies only in production, which is why the rule is written down rather
 *     than left to be noticed.
 *   - `react/no-danger` is an error here, so this emits React elements. No
 *     highlighted markup is ever handed to `dangerouslySetInnerHTML`.
 *
 * The palette is the one `JsonEditor` already uses, for the same reason: the
 * four status hues belong to statuses, so a string is not allowed to be "ok
 * green". Three tiers do the work — teal for the thing being named (a command, a
 * keyword, a JSON key), full-strength ink for the data, muted for scaffolding
 * and comments — and every colour is a token, so `.surface-ink` carries them
 * into either theme without being told.
 */

export type CodeLanguage = 'bash' | 'json' | 'http' | 'javascript' | 'python' | 'php' | 'text';

/** Maps the free-text `label` the docs pass to a grammar. */
export function normalizeLanguage(label?: string): CodeLanguage {
  switch (label?.trim().toLowerCase()) {
    case 'bash':
    case 'sh':
    case 'shell':
    case 'zsh':
    case 'console':
    case 'curl':
      return 'bash';
    case 'json':
      return 'json';
    case 'http':
      return 'http';
    case 'js':
    case 'jsx':
    case 'javascript':
    case 'node':
    case 'nodejs':
    case 'ts':
    case 'tsx':
    case 'typescript':
      return 'javascript';
    case 'py':
    case 'python':
      return 'python';
    case 'php':
      return 'php';
    default:
      return 'text';
  }
}

const TOKEN_CLASS: Record<string, string> = {
  comment: 'italic text-muted-foreground',
  string: 'text-foreground',
  key: 'text-primary',
  keyword: 'font-medium text-primary',
  builtin: 'text-accent-foreground',
  member: 'text-foreground',
  variable: 'text-accent-foreground',
  number: 'text-foreground',
  literal: 'italic text-muted-foreground',
  flag: 'text-muted-foreground',
  operator: 'text-muted-foreground',
  punctuation: 'text-muted-foreground',
};

/**
 * One regex per language, alternation ordered so the greediest context wins
 * first: a `#` inside a quoted string must not open a comment, and a comment
 * must swallow an apostrophe rather than let it open a string.
 *
 * Two conventions the scanner relies on:
 *
 *   - A group named `pre` is the boundary a token needed in order to be found
 *     at all, not part of the token; it is emitted as an operator. That is how
 *     `bash` tells the command `railhook` from the directory `~/.config/railhook`
 *     without a lookbehind — Safari only grew lookbehind in 16.4, and a
 *     lookbehind in a module-level literal is a parse error that takes the whole
 *     bundle down rather than degrading.
 *   - Leading whitespace a boundary-hungry alternative absorbed is handed back
 *     as plain text.
 */
const GRAMMARS: Partial<Record<CodeLanguage, RegExp>> = {
  bash: /(?<comment>#[^\n]*)|(?<string>'(?:[^'\\]|\\[\s\S])*'|"(?:[^"\\]|\\[\s\S])*")|(?<variable>\$\{[^}\n]*\}|\$[A-Za-z_]\w*)|(?<flag>(?:^|[ \t])--?[A-Za-z][\w-]*)|(?<pre>^|[|;&]{1,2}|\$\()[ \t]*(?<keyword>(?:curl|openssl|printf|echo|export|cut|npm|pip|composer|docker|railhook|git|node|python3?|bash|sh|make|sudo|exit|source|cd)\b)|(?<number>\b\d+(?:\.\d+)?\b)|(?<operator>\\\n|\|\||&&|[|;])/gm,

  json: /(?<key>"(?:[^"\\]|\\[\s\S])*"(?=\s*:))|(?<string>"(?:[^"\\]|\\[\s\S])*")|(?<literal>\b(?:true|false|null)\b)|(?<number>-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|(?<punctuation>[{}[\],:])/g,

  // The status line and the header names carry the structure; whatever follows
  // the colon is a value and stays ink.
  http: /(?<comment>[ \t]*#[^\n]*)|(?<keyword>^(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b|^HTTP\/[\d.]+(?:[ \t]+\d{3})?)|(?<key>^[A-Za-z][A-Za-z0-9-]*(?=:))/gm,

  javascript:
    /(?<comment>\/\/[^\n]*|\/\*[\s\S]*?\*\/)|(?<string>'(?:[^'\\\n]|\\[\s\S])*'|"(?:[^"\\\n]|\\[\s\S])*"|`(?:[^`\\]|\\[\s\S])*`)|(?<member>\.[A-Za-z_$][\w$]*)|(?<keyword>\b(?:import|export|default|from|as|const|let|var|function|return|await|async|new|class|extends|implements|interface|type|if|else|for|while|do|of|in|try|catch|finally|throw|typeof|instanceof)\b)|(?<literal>\b(?:true|false|null|undefined|this)\b)|(?<builtin>\b(?:crypto|Buffer|JSON|Math|Date|Object|Number|String|Array|Promise|console|process)\b)|(?<number>\b\d+(?:\.\d+)?\b)|(?<key>\b[A-Za-z_$][\w$]*(?=\s*:))/g,

  python:
    /(?<comment>#[^\n]*)|(?<string>"""[\s\S]*?"""|'''[\s\S]*?'''|[rbf]?"(?:[^"\\\n]|\\[\s\S])*"|[rbf]?'(?:[^'\\\n]|\\[\s\S])*')|(?<keyword>\b(?:import|from|def|return|class|if|elif|else|for|while|in|not|and|or|is|with|as|pass|raise|try|except|finally|lambda|yield|assert|global|async|await)\b)|(?<literal>\b(?:True|False|None|self)\b)|(?<builtin>\b(?:print|len|int|str|float|bool|dict|list|bytes|range|open|abs)\b)|(?<number>\b\d+(?:\.\d+)?\b)|(?<key>\b[A-Za-z_]\w*(?=\s*=(?!=)))/g,

  php: /(?<comment>\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/)|(?<string>'(?:[^'\\\n]|\\[\s\S])*'|"(?:[^"\\\n]|\\[\s\S])*")|(?<keyword><\?php|\?>|\b(?:use|new|function|return|class|interface|extends|implements|namespace|public|private|protected|static|const|echo|if|else|foreach|as|try|catch|finally|throw)\b)|(?<literal>\b(?:true|false|null)\b)|(?<variable>\$[A-Za-z_]\w*)|(?<builtin>\b(?:getenv|count|strlen|json_encode|json_decode)\b)|(?<number>\b\d+(?:\.\d+)?\b)|(?<key>\b[A-Za-z_]\w*(?=\s*:(?!:)))|(?<operator>=>|->)/g,
};

/** A shell string whose body is a JSON document — `curl -d '{…}'`. */
const SHELL_JSON = /^(['"])\s*[[{]/;

/** `$VAR` and `${VAR}` inside a double-quoted shell string, where the shell does expand them. */
const SHELL_INTERPOLATION = /\$\{[^}\n]*\}|\$[A-Za-z_]\w*/g;

/** Long enough that no sample reaches it, short enough that a pathological paste cannot hang the tab. */
const MAX_HIGHLIGHTED_CHARS = 40_000;

function span(key: string, kind: string, text: string): ReactNode {
  return (
    <span key={key} className={TOKEN_CLASS[kind]}>
      {text}
    </span>
  );
}

function matchedGroup(groups: Record<string, string | undefined> | undefined): string | undefined {
  if (!groups) return undefined;
  for (const name of Object.keys(groups)) {
    if (name !== 'pre' && groups[name] !== undefined) return name;
  }
  return undefined;
}

/**
 * A quoted shell word, which is very often not shell at all.
 *
 * The 25-line `curl` that prompted this work is nine parts JSON body; leaving it
 * as one flat string token would have changed nothing about how it reads. The
 * double-quoted case is milder but just as common — every quickstart sample
 * carries an `"Authorization: Bearer $ACCESS_TOKEN"`.
 */
function pushShellString(text: string, out: ReactNode[], key: string): void {
  const quote = text[0];

  if (SHELL_JSON.test(text)) {
    out.push(span(`${key}-open`, 'punctuation', quote));
    scan(text.slice(1, text.length - 1), 'json', out, `${key}-json`);
    out.push(span(`${key}-close`, 'punctuation', quote));
    return;
  }

  if (quote !== '"' || !text.includes('$')) {
    out.push(span(key, 'string', text));
    return;
  }

  SHELL_INTERPOLATION.lastIndex = 0;
  let cursor = 0;
  let index = 0;
  let match: RegExpExecArray | null;
  while ((match = SHELL_INTERPOLATION.exec(text)) !== null) {
    if (match.index > cursor) out.push(span(`${key}-s${index}`, 'string', text.slice(cursor, match.index)));
    out.push(span(`${key}-v${index}`, 'variable', match[0]));
    cursor = match.index + match[0].length;
    index += 1;
  }
  if (cursor < text.length) out.push(span(`${key}-s${index}`, 'string', text.slice(cursor)));
}

function scan(code: string, language: CodeLanguage, out: ReactNode[], keyPrefix: string): void {
  const grammar = GRAMMARS[language];
  if (!grammar) {
    out.push(code);
    return;
  }

  grammar.lastIndex = 0;
  let cursor = 0;
  let index = 0;
  let match: RegExpExecArray | null;

  while ((match = grammar.exec(code)) !== null) {
    // A zero-width match would spin forever; nudge past it.
    if (match[0] === '') {
      grammar.lastIndex += 1;
      continue;
    }

    const kind = matchedGroup(match.groups);
    if (!kind) continue;

    if (match.index > cursor) out.push(code.slice(cursor, match.index));
    cursor = match.index + match[0].length;

    const key = `${keyPrefix}-${index++}`;
    let text = match[0];

    const pre = match.groups?.pre;
    if (pre) {
      out.push(span(`${key}-pre`, 'operator', pre));
      text = text.slice(pre.length);
    }

    const lead = /^[ \t\n]*/.exec(text)?.[0] ?? '';
    if (lead) {
      out.push(lead);
      text = text.slice(lead.length);
    }
    if (!text) continue;

    if (language === 'bash' && kind === 'string') {
      pushShellString(text, out, key);
      continue;
    }

    out.push(span(key, kind, text));
  }

  if (cursor < code.length) out.push(code.slice(cursor));
}

/**
 * An HTTP sample is two languages: a head, a blank line, then a body that is
 * almost always JSON. Handing the whole thing to the HTTP grammar would flatten
 * the half that carries the payload.
 */
function scanHttp(code: string, out: ReactNode[]): void {
  const separator = /\n[ \t]*\n/.exec(code);
  const body = separator ? code.slice(separator.index + separator[0].length) : '';

  if (!separator || !/^\s*[[{]/.test(body)) {
    scan(code, 'http', out, 'http');
    return;
  }

  scan(code.slice(0, separator.index), 'http', out, 'head');
  out.push(separator[0]);
  scan(body, 'json', out, 'body');
}

export function highlight(code: string, language: CodeLanguage): ReactNode[] {
  if (language === 'text' || code.length > MAX_HIGHLIGHTED_CHARS) return [code];

  const out: ReactNode[] = [];
  if (language === 'http') scanHttp(code, out);
  else scan(code, language, out, 'tok');
  return out;
}

/**
 * Renders `code` as coloured spans. Purely presentational: the caller still owns
 * the `<pre>`, so copy-to-clipboard keeps working off the original string and
 * long lines keep scrolling inside whatever box the caller drew.
 */
export default function SyntaxHighlight({ code, language }: { code: string; language: CodeLanguage }) {
  const nodes = useMemo(() => highlight(code, language), [code, language]);
  return <>{nodes}</>;
}
