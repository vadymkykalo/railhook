import { Fragment, type ReactNode } from 'react';

/**
 * Renders a description that came out of `openapi.yaml`.
 *
 * Spec descriptions are CommonMark, but `react/no-danger` is an error in this
 * repo and piping spec text through `dangerouslySetInnerHTML` would be exactly
 * the hole that rule exists to close. In practice springdoc's descriptions use
 * three constructs and no more: backtick code spans, `**bold**`, and blank-line
 * paragraph breaks. So this handles those three as real React elements and
 * leaves everything else as literal text — no markdown dependency, and nothing
 * from the spec can ever become markup.
 */

const TOKEN = /(`[^`]+`|\*\*[^*]+\*\*)/g;

function inline(text: string, keyPrefix: string): ReactNode[] {
  return text.split(TOKEN).map((part, i) => {
    const key = `${keyPrefix}-${i}`;
    if (part.length > 2 && part.startsWith('`') && part.endsWith('`')) {
      return (
        <code key={key} className="rounded bg-secondary px-1 py-0.5 font-mono text-[0.9em]">
          {part.slice(1, -1)}
        </code>
      );
    }
    if (part.length > 4 && part.startsWith('**') && part.endsWith('**')) {
      return (
        <strong key={key} className="font-medium text-foreground">
          {part.slice(2, -2)}
        </strong>
      );
    }
    return <Fragment key={key}>{part}</Fragment>;
  });
}

export default function SpecText({ text, className }: { text: string; className?: string }) {
  const paragraphs = text.split(/\n{2,}/).filter((p) => p.trim().length > 0);
  if (paragraphs.length === 0) return null;
  return (
    <div className={className}>
      {paragraphs.map((paragraph, i) => (
        <p key={i} className={i > 0 ? 'mt-2' : undefined}>
          {inline(paragraph.replace(/\n/g, ' '), String(i))}
        </p>
      ))}
    </div>
  );
}
