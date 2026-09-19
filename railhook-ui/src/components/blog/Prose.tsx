import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import SyntaxHighlight, { normalizeLanguage } from '../SyntaxHighlight';
import type { Block, Inline } from '../../lib/markdown';
import { FIGURES } from './figures';

/**
 * A post's parsed Markdown, rendered as elements.
 *
 * The typography is the site's, set here rather than through a prose plugin: the pages already
 * own a display face, a rail colour and a code surface, and a second typographic system would
 * drift from them. Measure is capped at ~68 characters, which is what the rest of the public
 * pages read at.
 *
 * Nothing from a file is ever handed to `dangerouslySetInnerHTML` — `src/lib/markdown.ts`
 * produces data, and this walks it.
 */

function Nodes({ nodes }: { nodes: Inline[] }): ReactNode {
  return (
    <>
      {nodes.map((node, index) => {
        switch (node.type) {
          case 'text':
            return node.value;
          case 'code':
            return (
              <code
                key={index}
                className="rounded bg-muted px-1 py-0.5 font-mono text-[0.85em] text-foreground [overflow-wrap:anywhere]"
              >
                {node.value}
              </code>
            );
          case 'strong':
            return (
              <strong key={index} className="font-semibold text-foreground">
                <Nodes nodes={node.children} />
              </strong>
            );
          case 'em':
            return (
              <em key={index}>
                <Nodes nodes={node.children} />
              </em>
            );
          case 'image':
            return (
              <img
                key={index}
                src={node.src}
                alt={node.alt}
                className="inline-block h-[1.1em] w-auto align-[-0.15em]"
                loading="lazy"
              />
            );
          case 'link': {
            if (/^https?:\/\//i.test(node.href)) {
              return (
                <a
                  key={index}
                  href={node.href}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="font-medium text-primary underline-offset-2 hover:underline"
                >
                  <Nodes nodes={node.children} />
                </a>
              );
            }
            if (node.href.startsWith('#') || node.href.startsWith('/docs/')) {
              return (
                <a key={index} href={node.href} className="font-medium text-primary underline-offset-2 hover:underline">
                  <Nodes nodes={node.children} />
                </a>
              );
            }
            return (
              <Link key={index} to={node.href} className="font-medium text-primary underline-offset-2 hover:underline">
                <Nodes nodes={node.children} />
              </Link>
            );
          }
        }
      })}
    </>
  );
}

function CodeBlock({ language, code }: { language: string; code: string }) {
  return (
    <div className="surface-ink my-7 overflow-x-auto rounded-xl border border-rail p-4 sm:p-5">
      <pre className="font-mono text-[12.5px] leading-[1.7] [font-variant-ligatures:none] sm:text-[13px]">
        <code className="block w-max min-w-full text-foreground">
          <SyntaxHighlight code={code} language={normalizeLanguage(language)} />
        </code>
      </pre>
    </div>
  );
}

function Table({ head, rows }: { head: Inline[][]; rows: Inline[][][] }) {
  return (
    <div className="my-8 overflow-x-auto rounded-xl border border-rail">
      <table className="w-full min-w-[34rem] border-collapse text-[14px]">
        <thead>
          <tr className="border-b border-rail bg-muted/60">
            {head.map((cell, index) => (
              <th key={index} scope="col" className="mono-label px-4 py-3 text-left align-bottom">
                <Nodes nodes={cell} />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, rowIndex) => (
            <tr key={rowIndex} className="border-b border-rail last:border-b-0">
              {row.map((cell, cellIndex) => (
                <td
                  key={cellIndex}
                  className={
                    cellIndex === 0
                      ? 'px-4 py-3 align-top font-medium text-foreground'
                      : 'px-4 py-3 align-top text-muted-foreground'
                  }
                >
                  <Nodes nodes={cell} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * The measure prose is read at. Figures, tables and code blocks are deliberately not held to
 * it — a comparison table at 68 characters wraps every cell — so it sits on the text blocks
 * rather than on the container.
 */
const MEASURE = 'max-w-[68ch]';

function One({ block }: { block: Block }): ReactNode {
  switch (block.type) {
    case 'heading':
      return block.level === 2 ? (
        <h2
          id={block.id}
          className={`${MEASURE} mt-12 scroll-mt-24 font-display text-[1.6rem] font-bold leading-[1.15] tracking-[-0.025em] text-foreground first:mt-0`}
        >
          {block.text}
        </h2>
      ) : (
        <h3 id={block.id} className={`${MEASURE} mt-8 scroll-mt-24 text-[1.15rem] font-semibold text-foreground`}>
          {block.text}
        </h3>
      );
    case 'paragraph':
      return (
        <p className={`${MEASURE} mt-5 text-[1.0625rem] leading-[1.75] text-muted-foreground`}>
          <Nodes nodes={block.content} />
        </p>
      );
    case 'list':
      return block.ordered ? (
        <ol className={`${MEASURE} mt-5 list-decimal space-y-2 pl-6 text-[1.0625rem] leading-[1.75] text-muted-foreground marker:font-mono marker:text-primary`}>
          {block.items.map((item, index) => (
            <li key={index} className="pl-1">
              <Nodes nodes={item} />
            </li>
          ))}
        </ol>
      ) : (
        <ul className={`${MEASURE} mt-5 list-disc space-y-2 pl-6 text-[1.0625rem] leading-[1.75] text-muted-foreground marker:text-primary`}>
          {block.items.map((item, index) => (
            <li key={index} className="pl-1">
              <Nodes nodes={item} />
            </li>
          ))}
        </ul>
      );
    case 'quote':
      return (
        <blockquote className={`${MEASURE} my-8 border-l-2 border-primary pl-5 text-[1.125rem] italic leading-[1.7] text-foreground`}>
          <Nodes nodes={block.content} />
        </blockquote>
      );
    case 'code':
      return <CodeBlock language={block.language} code={block.code} />;
    case 'table':
      return <Table head={block.head} rows={block.rows} />;
    case 'figure': {
      const Drawing = FIGURES[block.key];
      // A post naming a figure nobody drew renders nothing rather than a broken box; the
      // `every figure a post names exists` test is what stops that reaching a reader.
      return Drawing ? <Drawing /> : null;
    }
  }
}

export default function Prose({ blocks }: { blocks: Block[] }) {
  return (
    <div className="min-w-0">
      {blocks.map((block, index) => (
        <One key={index} block={block} />
      ))}
    </div>
  );
}
