import * as React from "react"
import { cn } from "../../lib/utils"

/** What a checkbox header is recorded as while columns are collected; never a real header text. */
const SELECT_COLUMN = "__select__"

/**
 * Copies each column's header text onto its body cells as `data-label`, which is what the phone
 * layout (`.rh-table-stack` in index.css) prints in front of every value. A column whose header
 * holds a checkbox is the selection column (`data-cell="select"`), a header with no text is an
 * actions column (`data-cell="actions"`), and a cell spanning columns — an empty state, a
 * loading row — is `data-cell="wide"`. Attributes only, so observing child and text changes
 * cannot loop on its own writes.
 */
function labelCells(table: HTMLTableElement) {
  const headRow = table.tHead?.rows[0]
  if (!headRow) return
  const columns: string[] = []
  for (const th of Array.from(headRow.cells)) {
    const isSelect = !!th.querySelector('input[type="checkbox"], [role="checkbox"]')
    const text = isSelect ? SELECT_COLUMN : (th.textContent ?? "").trim()
    for (let i = 0; i < (th.colSpan || 1); i++) columns.push(text)
  }
  const set = (cell: HTMLTableCellElement, name: string, value: string | null) => {
    if (value === null) {
      if (cell.hasAttribute(name)) cell.removeAttribute(name)
    } else if (cell.getAttribute(name) !== value) {
      cell.setAttribute(name, value)
    }
  }
  for (const body of Array.from(table.tBodies)) {
    for (const row of Array.from(body.rows)) {
      let column = 0
      for (const cell of Array.from(row.cells)) {
        const span = cell.colSpan || 1
        const header = columns[column]
        const kind = span > 1 ? "wide" : header === SELECT_COLUMN ? "select" : header ? null : "actions"
        set(cell, "data-cell", kind)
        set(cell, "data-label", kind ? null : header ?? null)
        column += span
      }
    }
  }
}

interface TableProps extends React.HTMLAttributes<HTMLTableElement> {
  /** Below `sm`, lay the rows out as cards. On by default; a table that reads fine narrow can opt out. */
  stack?: boolean
}

const Table = React.forwardRef<HTMLTableElement, TableProps>(
  ({ className, stack = true, ...props }, ref) => {
    const inner = React.useRef<HTMLTableElement | null>(null)
    const setRef = React.useCallback(
      (node: HTMLTableElement | null) => {
        inner.current = node
        if (typeof ref === "function") ref(node)
        else if (ref) ref.current = node
      },
      [ref]
    )

    React.useLayoutEffect(() => {
      const table = inner.current
      if (!stack || !table) return
      labelCells(table)
      if (typeof MutationObserver === "undefined") return
      const observer = new MutationObserver(() => labelCells(table))
      observer.observe(table, { childList: true, subtree: true, characterData: true })
      return () => observer.disconnect()
    }, [stack])

    return (
      <div className={cn("relative w-full overflow-auto", stack && "rh-table-stack")}>
        <table
          ref={setRef}
          className={cn("w-full caption-bottom text-sm", className)}
          {...props}
        />
      </div>
    )
  }
)
Table.displayName = "Table"

const TableHeader = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement>
>(({ className, ...props }, ref) => (
  <thead ref={ref} className={cn("[&_tr]:border-b", className)} {...props} />
))
TableHeader.displayName = "TableHeader"

const TableBody = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement>
>(({ className, ...props }, ref) => (
  <tbody
    ref={ref}
    className={cn("[&_tr:last-child]:border-0", className)}
    {...props}
  />
))
TableBody.displayName = "TableBody"

const TableFooter = React.forwardRef<
  HTMLTableSectionElement,
  React.HTMLAttributes<HTMLTableSectionElement>
>(({ className, ...props }, ref) => (
  <tfoot
    ref={ref}
    className={cn(
      "border-t border-rail bg-secondary/40 font-medium [&>tr]:last:border-b-0",
      className
    )}
    {...props}
  />
))
TableFooter.displayName = "TableFooter"

const TableRow = React.forwardRef<
  HTMLTableRowElement,
  React.HTMLAttributes<HTMLTableRowElement>
>(({ className, ...props }, ref) => (
  <tr
    ref={ref}
    className={cn(
      "border-b border-rail transition-colors hover:bg-secondary/50 data-[state=selected]:bg-secondary",
      className
    )}
    {...props}
  />
))
TableRow.displayName = "TableRow"

const TableHead = React.forwardRef<
  HTMLTableCellElement,
  React.ThHTMLAttributes<HTMLTableCellElement>
>(({ className, ...props }, ref) => (
  <th
    ref={ref}
    className={cn(
      "h-9 px-4 text-left align-middle font-mono text-[11px] font-medium uppercase tracking-[0.08em] text-muted-foreground [&:has([role=checkbox])]:pr-0",
      className
    )}
    {...props}
  />
))
TableHead.displayName = "TableHead"

const TableCell = React.forwardRef<
  HTMLTableCellElement,
  React.TdHTMLAttributes<HTMLTableCellElement>
>(({ className, ...props }, ref) => (
  <td
    ref={ref}
    className={cn("px-4 py-3 align-middle [&:has([role=checkbox])]:pr-0", className)}
    {...props}
  />
))
TableCell.displayName = "TableCell"

const TableCaption = React.forwardRef<
  HTMLTableCaptionElement,
  React.HTMLAttributes<HTMLTableCaptionElement>
>(({ className, ...props }, ref) => (
  <caption
    ref={ref}
    className={cn("mt-4 text-sm text-muted-foreground", className)}
    {...props}
  />
))
TableCaption.displayName = "TableCaption"

export {
  Table,
  TableHeader,
  TableBody,
  TableFooter,
  TableHead,
  TableRow,
  TableCell,
  TableCaption,
}
