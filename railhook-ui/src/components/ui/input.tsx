import * as React from "react"
import { cn } from "../../lib/utils"

export interface InputProps
  extends React.InputHTMLAttributes<HTMLInputElement> {}

/* Height and surface restate the global `:where(input, textarea)` rule in
   index.css instead of out-specifying it. The stock shadcn `h-10` put every
   field 4px taller than the `h-9` Button beside it, and `bg-background`
   painted a paper-grey field onto a white card. Focus is deliberately absent:
   index.css owns the single form-field ring (border + 3px soft shadow), so a
   ring here would be a second, differently-shaped focus treatment. */
const Input = React.forwardRef<HTMLInputElement, InputProps>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          "flex h-9 w-full rounded-md border border-input bg-card px-3 py-2 text-sm file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50",
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Input.displayName = "Input"

export { Input }
