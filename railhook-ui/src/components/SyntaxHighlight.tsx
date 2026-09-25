import { useMemo, type ReactNode } from 'react';

/**
 * Read-only syntax highlighting for the code on the public pages: the blog's
 * fenced blocks, the landing page's samples, the install and tester commands.
 *
 * Why not CodeMirror, which is already a dependency? Because CodeMirror
 * highlights with Lezer, and Lezer is one prebuilt parser table per language.
 * `JsonEditor` pulls in exactly one of them — JSON — and its chunk is 336 kB.
 * The public pages need bash, JSON, HTTP, JS/TS, Java, Python, PHP, YAML and
 * SQL; that many grammars would cost several times the pages they colour, for
 * samples nobody edits. A scanner sized to short, static samples is the right
 * shape for text that is never parsed twice. Prism and highlight.js are the
 * same trade at a smaller discount, plus a second theming system to keep in
 * step with tokens.
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
 *     highlighted markup is ever handed to `dangerouslySetInnerHTML`, which is
 *     also why a sample containing `<script>` is shown rather than run.
 *
 * The palette is its own: the `--code-*` tokens in `index.css`, declared for
 * paper, for the dark page and for the ink code surface. It is an IDE's
 * palette kept close to the monochrome system: ink keywords and calls, greys
 * for types and punctuation, and three muted hues (olive keys, terracotta
 * strings, raspberry numbers) for what a reader scans for. Two rules keep it
 * inside the product: none
 * of the four status hues is used (a string is never "ok green", a number never
 * "halt red"), and every colour clears WCAG AA against the surface it is
 * declared for. Comments and punctuation are the recessive tier.
 */

export type CodeLanguage =
  | 'bash'
  | 'json'
  | 'http'
  | 'javascript'
  | 'java'
  | 'python'
  | 'php'
  | 'yaml'
  | 'sql'
  | 'text';

/** Maps the free-text `label` a caller passes, or a Markdown fence's language, to a grammar. */
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
    case 'mjs':
    case 'javascript':
    case 'node':
    case 'nodejs':
    case 'ts':
    case 'tsx':
    case 'typescript':
      return 'javascript';
    case 'java':
      return 'java';
    case 'py':
    case 'python':
      return 'python';
    case 'php':
      return 'php';
    case 'yaml':
    case 'yml':
      return 'yaml';
    case 'sql':
    case 'postgres':
    case 'postgresql':
      return 'sql';
    default:
      return 'text';
  }
}

/** Token kind → the class `index.css` colours it with. Several kinds share a colour on purpose. */
const TOKEN_CLASS: Record<string, string> = {
  comment: 'tok-comment',
  string: 'tok-string',
  regex: 'tok-string',
  key: 'tok-key',
  keyword: 'tok-keyword',
  builtin: 'tok-type',
  type: 'tok-type',
  function: 'tok-function',
  annotation: 'tok-annotation',
  member: 'tok-member',
  variable: 'tok-variable',
  number: 'tok-number',
  literal: 'tok-number',
  constant: 'tok-number',
  flag: 'tok-flag',
  operator: 'tok-punct',
  punctuation: 'tok-punct',
};

/**
 * One regex per language, alternation ordered so the greediest context wins
 * first: a `#` inside a quoted string must not open a comment, and a comment
 * must swallow an apostrophe rather than let it open a string. Where two
 * alternatives can start at the same character the earlier one wins, which is
 * how `if (` stays a keyword instead of becoming a call.
 *
 * Conventions the scanner relies on:
 *
 *   - A group named `pre` is the boundary a token needed in order to be found
 *     at all, not part of the token; it is emitted as an operator. That is how
 *     `bash` tells the command `railhook` from the directory `~/.config/railhook`,
 *     JavaScript a regex literal from a division, and YAML a key from a value,
 *     without a lookbehind — Safari only grew lookbehind in 16.4, and a
 *     lookbehind in a module-level literal is a parse error that takes the whole
 *     bundle down rather than degrading.
 *   - Leading whitespace a boundary-hungry alternative absorbed is handed back
 *     as plain text.
 *   - A `member` (`.name`) is classified after the match, by its spelling and
 *     by what follows it: a call, a type, a CONSTANT or a plain property. It is
 *     matched whole so that `.default` or `.type` is never taken for a keyword.
 *   - Types are recognised by spelling — `PascalCase` (or a lone capital, a type
 *     parameter) is a type, `UPPER_CASE` a constant — which is what a reader's
 *     eye does too, and needs no symbol table.
 */
const GRAMMARS: Partial<Record<CodeLanguage, RegExp>> = {
  bash: /(?<comment>#[^\n]*)|(?<string>'(?:[^'\\]|\\[\s\S])*'|"(?:[^"\\]|\\[\s\S])*")|(?<variable>\$\{[^}\n]*\}|\$[A-Za-z_]\w*)|(?<flag>(?:^|[ \t])--?[A-Za-z][\w-]*)|(?<pre>^|[|;&]{1,2}|\$\()[ \t]*(?<keyword>(?:curl|openssl|printf|echo|export|cut|npm|npx|pip|composer|docker|railhook|git|node|python3?|bash|sh|make|sudo|exit|source|cd|date)\b)|(?<number>\b\d+(?:\.\d+)?\b)|(?<operator>\\\n|\|\||&&|[|;])/gm,

  json: /(?<key>"(?:[^"\\]|\\[\s\S])*"(?=\s*:))|(?<string>"(?:[^"\\]|\\[\s\S])*")|(?<literal>\b(?:true|false|null)\b)|(?<number>-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b)|(?<punctuation>[{}[\],:])/g,

  // The request line and the header names carry the structure; whatever follows
  // the colon is a value and stays ink.
  http: /(?<comment>[ \t]*#[^\n]*)|(?<keyword>^(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\b)|(?<literal>\bHTTP\/[\d.]+(?:[ \t]+\d{3})?)|(?<key>^[A-Za-z][A-Za-z0-9-]*(?=:))/gm,

  // JavaScript and TypeScript share one grammar: what TypeScript adds is a handful of keywords and
  // primitive type names, and a PascalCase name is a type in either.
  javascript:
    /(?<comment>\/\/[^\n]*|\/\*[\s\S]*?\*\/)|(?<string>'(?:[^'\\\n]|\\[\s\S])*'|"(?:[^"\\\n]|\\[\s\S])*"|`(?:[^`\\]|\\[\s\S])*`)|(?<pre>[(,=:[!&|?{};][ \t]*)(?<regex>\/(?![*/])(?:[^/\\\n[]|\\.|\[(?:[^\]\\\n]|\\.)*\])+\/[dgimsuyv]*)|(?<member>\.[A-Za-z_$][\w$]*)|(?<keyword>\b(?:import|export|default|from|as|const|let|var|function|return|await|async|new|class|extends|implements|interface|type|enum|namespace|declare|readonly|abstract|public|private|protected|static|keyof|satisfies|if|else|for|while|do|of|in|switch|case|break|continue|try|catch|finally|throw|typeof|instanceof|delete|void|yield)\b)|(?<literal>\b(?:true|false|null|undefined|this|NaN|Infinity)\b)|(?<builtin>\b(?:crypto|console|process|window|document|globalThis|JSON|Math)\b)|(?<type>\b(?:string|number|boolean|unknown|never|any|bigint|symbol|object)\b|\b[A-Z](?:[a-z0-9]\w*)?\b)|(?<constant>\b[A-Z][A-Z0-9_]*[A-Z0-9]\b)|(?<number>\b(?:0[xX][\da-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?n?)\b)|(?<function>\b[A-Za-z_$][\w$]*(?=\s*(?:<[\w\s,<>[\]|]*>)?\())|(?<key>\b[A-Za-z_$][\w$]*(?=\s*\??:))|(?<annotation>@[A-Za-z_$][\w$.]*)|(?<operator>=>|[-+*/%=!<>&|?^~]+)|(?<punctuation>[{}()[\];,.:])/g,

  java: /(?<comment>\/\/[^\n]*|\/\*[\s\S]*?\*\/)|(?<string>"""[\s\S]*?"""|"(?:[^"\\\n]|\\[\s\S])*"|'(?:[^'\\\n]|\\[\s\S])*')|(?<annotation>@(?!interface\b)[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)|(?<keyword>\b(?:abstract|assert|break|case|catch|class|continue|default|do|else|enum|extends|final|finally|for|if|implements|import|instanceof|interface|native|new|package|permits|private|protected|public|record|return|sealed|static|super|switch|synchronized|this|throw|throws|transient|try|var|void|volatile|while|yield)\b)|(?<literal>\b(?:true|false|null)\b)|(?<type>\b(?:boolean|byte|char|double|float|int|long|short)\b|\b[A-Z](?:[a-z0-9]\w*)?\b)|(?<constant>\b[A-Z][A-Z0-9_]*[A-Z0-9]\b)|(?<number>\b(?:0[xX][\da-fA-F_]+|\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?)[lLfFdD]?\b)|(?<function>\b[a-z_$][\w$]*(?=\s*\())|(?<operator>->|::|[-+*/%=!<>&|?^~:]+)|(?<punctuation>[{}()[\];,.])/g,

  python:
    /(?<comment>#[^\n]*)|(?<string>"""[\s\S]*?"""|'''[\s\S]*?'''|[rbfRBF]{0,2}"(?:[^"\\\n]|\\[\s\S])*"|[rbfRBF]{0,2}'(?:[^'\\\n]|\\[\s\S])*')|(?<annotation>^[ \t]*@[A-Za-z_][\w.]*)|(?<keyword>\b(?:import|from|def|return|class|if|elif|else|for|while|in|not|and|or|is|with|as|pass|raise|try|except|finally|lambda|yield|assert|global|nonlocal|async|await|del|break|continue)\b)|(?<literal>\b(?:True|False|None|self|cls)\b)|(?<builtin>\b(?:print|len|int|str|float|bool|dict|list|tuple|set|bytes|range|open|abs|isinstance|super)\b)|(?<type>\b[A-Z](?:[a-z0-9]\w*)?\b)|(?<constant>\b[A-Z][A-Z0-9_]*[A-Z0-9]\b)|(?<number>\b\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?j?\b)|(?<function>\b[A-Za-z_]\w*(?=\())|(?<operator>->|[-+*/%=!<>&|^~:@]+)|(?<punctuation>[{}()[\];,.])/gm,

  php: /(?<comment>\/\/[^\n]*|#[^\n]*|\/\*[\s\S]*?\*\/)|(?<string>'(?:[^'\\\n]|\\[\s\S])*'|"(?:[^"\\\n]|\\[\s\S])*")|(?<keyword><\?php|\?>|\b(?:use|new|function|return|class|interface|extends|implements|namespace|public|private|protected|static|const|echo|if|else|foreach|as|try|catch|finally|throw)\b)|(?<literal>\b(?:true|false|null)\b)|(?<variable>\$[A-Za-z_]\w*)|(?<builtin>\b(?:getenv|count|strlen|json_encode|json_decode)\b)|(?<type>\b[A-Z](?:[a-z0-9]\w*)?\b)|(?<number>\b\d+(?:\.\d+)?\b)|(?<key>\b[A-Za-z_]\w*(?=\s*:(?!:)))|(?<function>\b[A-Za-z_]\w*(?=\s*\())|(?<operator>=>|->|::)/g,

  // A key is the name before `: ` at the start of a line, after any indentation and list dash.
  // What follows is data, and only data with a recognisable shape is coloured.
  yaml: /(?<comment>(?:^|[ \t])#[^\n]*)|(?<string>"(?:[^"\\\n]|\\.)*"|'(?:[^'\n]|'')*')|(?<pre>^[ \t]*(?:-[ \t]+)?)(?<key>[A-Za-z_][\w.-]*|"[^"\n]*")(?=[ \t]*:(?:[ \t]|$))|(?<literal>\b(?:true|false|null|yes|no|on|off)\b|~)|(?<number>\b\d+(?:\.\d+)?\b)|(?<punctuation>^[ \t]*-(?=[ \t])|[:{}[\],|>])/gm,

  // Keywords are matched case-insensitively — `select` and `SELECT` are the same word.
  sql: /(?<comment>--[^\n]*|\/\*[\s\S]*?\*\/)|(?<string>'(?:[^']|'')*')|(?<variable>:[A-Za-z_]\w*|\$\d+)|(?<keyword>\b(?:select|insert|update|delete|into|values|set|from|where|and|or|not|in|is|as|on|using|join|left|right|inner|outer|full|cross|lateral|group|by|order|having|limit|offset|union|all|distinct|case|when|then|else|end|create|alter|drop|table|index|view|unique|primary|key|foreign|references|add|column|if|exists|default|constraint|returning|with|for|skip|locked|nowait|conflict|do|nothing|begin|commit|rollback|between|like|ilike|asc|desc|cascade|check)\b)|(?<literal>\b(?:true|false|null)\b)|(?<type>\b(?:integer|int|bigint|smallint|serial|bigserial|text|varchar|char|boolean|bool|uuid|jsonb|json|numeric|decimal|timestamptz|timestamp|interval|bytea)\b)|(?<number>\b\d+(?:\.\d+)?\b)|(?<function>\b[A-Za-z_]\w*(?=\())|(?<operator>[-+*/%=!<>|:]+)|(?<punctuation>[(),;.])/gi,
};

/** A shell string whose body is a JSON document — `curl -d '{…}'`. */
const SHELL_JSON = /^(['"])\s*[[{]/;

/** `$VAR` and `${VAR}` inside a double-quoted shell string, where the shell does expand them. */
const SHELL_INTERPOLATION = /\$\{[^}\n]*\}|\$[A-Za-z_]\w*/g;

/** What follows a `.name` when the name is being called: `(`, or `<T>(`. */
const CALL_FOLLOWS = /^\s*(?:<[\w\s,<>[\]|]*>)?\(/;
const CONSTANT_NAME = /^[A-Z][A-Z0-9_]*[A-Z0-9]$/;
const TYPE_NAME = /^[A-Z](?:[a-z0-9]\w*)?$/;

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

/** `.name`: a call, a nested type, a constant or a property, told apart by spelling and context. */
function pushMember(text: string, following: string, out: ReactNode[], key: string): void {
  const name = text.slice(1);
  let kind = 'member';
  if (CALL_FOLLOWS.test(following)) kind = 'function';
  else if (CONSTANT_NAME.test(name)) kind = 'constant';
  else if (TYPE_NAME.test(name)) kind = 'type';
  out.push(span(`${key}-dot`, 'punctuation', '.'));
  out.push(span(key, kind, name));
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

    if (kind === 'member') {
      pushMember(text, code.slice(cursor, cursor + 64), out, key);
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
