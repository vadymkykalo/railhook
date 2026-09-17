/**
 * Pretty-prints a JSON string, returning it unchanged when it is not JSON.
 *
 * <p>A webhook body is whatever the sender put in it: showing the raw text is the honest answer
 * for a payload that never parsed.
 */
export function formatJson(value: unknown): string {
  if (typeof value !== 'string') {
    // Callers hand this whatever an API gave them, and axios parses a JSON
    // body into an object whatever the content type claimed. Returning the
    // object unchanged made React try to render it.
    return value == null ? '' : JSON.stringify(value, null, 2);
  }
  try {
    JSON.parse(value);
  } catch {
    return value;
  }
  return reindent(value);
}

const INDENT = '  ';

/**
 * Re-indents JSON text that is already known to be valid, copying every string and number token
 * verbatim. `JSON.stringify(JSON.parse(x))` goes through a double, so an id like
 * 1234567890123456789 came back as 1234567890123456800 — on screen, and in the Transform
 * Studio's "Format", in the payload that is then sent.
 */
function reindent(text: string): string {
  let out = '';
  let depth = 0;
  let i = 0;
  const n = text.length;
  const isWs = (c: string) => c === ' ' || c === '\t' || c === '\n' || c === '\r';
  const nextSignificant = (from: number) => {
    let j = from;
    while (j < n && isWs(text[j])) j++;
    return text[j];
  };

  while (i < n) {
    const c = text[i];
    if (isWs(c)) {
      i++;
    } else if (c === '"') {
      let j = i + 1;
      while (j < n && text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      out += text.slice(i, j + 1);
      i = j + 1;
    } else if (c === '{' || c === '[') {
      const close = c === '{' ? '}' : ']';
      if (nextSignificant(i + 1) === close) {
        out += c + close;
        i = text.indexOf(close, i + 1) + 1;
      } else {
        depth++;
        out += c + '\n' + INDENT.repeat(depth);
        i++;
      }
    } else if (c === '}' || c === ']') {
      depth--;
      out += '\n' + INDENT.repeat(depth) + c;
      i++;
    } else if (c === ',') {
      out += ',\n' + INDENT.repeat(depth);
      i++;
    } else if (c === ':') {
      out += ': ';
      i++;
    } else {
      let j = i;
      while (j < n && !isWs(text[j]) && !',:]}'.includes(text[j])) j++;
      out += text.slice(i, j);
      i = j;
    }
  }
  return out;
}

/** Whether a string parses as JSON at all — for a form that only accepts one. */
export function isValidJson(value: string): boolean {
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}
