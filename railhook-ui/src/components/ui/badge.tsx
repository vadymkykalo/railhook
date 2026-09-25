import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "../../lib/utils"

/** Status variants use only the reserved status tokens; default and info never read as "fine". */
const badgeVariants = cva(
  "inline-flex items-center gap-1.5 border px-2 py-0.5 text-xs font-normal transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
  {
    variants: {
      variant: {
        default: "border-transparent bg-primary text-primary-foreground",
        secondary: "border-transparent bg-secondary text-secondary-foreground",
        outline: "border-rail text-muted-foreground",

        ok: "border-transparent bg-ok-soft text-ok",
        retry: "border-transparent bg-retry-soft text-retry",
        halt: "border-transparent bg-halt-soft text-halt",
        idle: "border-transparent bg-idle-soft text-idle",

        success: "border-transparent bg-ok-soft text-ok",
        warning: "border-transparent bg-retry-soft text-retry",
        destructive: "border-transparent bg-halt-soft text-halt",
        info: "border-transparent bg-accent text-accent-foreground",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <div className={cn(badgeVariants({ variant }), className)} {...props} />
  )
}

export { Badge, badgeVariants }
