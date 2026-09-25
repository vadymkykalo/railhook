export function formatJson(value: unknown): string {
  if (typeof value !== 'string') {
    // axios parses JSON bodies into objects whatever the content type claimed.
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

/** Copies number tokens verbatim: JSON.parse turns 1234567890123456789 into ...800. */
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

export function isValidJson(value: string): boolean {
  try {
    JSON.parse(value);
    return true;
  } catch {
    return false;
  }
}
