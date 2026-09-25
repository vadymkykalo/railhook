import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"
import { Loader2 } from "lucide-react"
import { cn } from "../../lib/utils"

const buttonVariants = cva(
  "inline-flex items-center justify-center gap-2 whitespace-nowrap border border-transparent text-[13px] font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50",
  {
    variants: {
      // Token foregrounds, not text-white: --halt/--ok lighten on ink and dark mode.
      variant: {
        default: "bg-primary text-primary-foreground hover:bg-primary-hover",
        destructive: "bg-halt text-destructive-foreground hover:bg-halt/90",
        outline: "border-input bg-transparent text-foreground hover:border-foreground",
        secondary: "bg-secondary text-secondary-foreground hover:bg-rail",
        ghost: "hover:bg-secondary hover:text-foreground",
        link: "link-ink",
        success: "bg-ok text-success-foreground hover:bg-ok/90",
      },
      size: {
        default: "h-9 px-4 max-sm:h-10",
        sm: "h-8 px-3 text-xs max-sm:h-10",
        lg: "h-10 px-4 text-[15px] font-normal",
        icon: "h-9 w-9 max-sm:h-10 max-sm:w-10",
        "icon-sm": "h-8 w-8 max-sm:h-10 max-sm:w-10",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
  isLoading?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, isLoading = false, disabled, children, ...props }, ref) => {
    // Slot needs exactly one child, so the spinner lives only in the <button> branch.
    if (asChild) {
      return (
        <Slot className={cn(buttonVariants({ variant, size, className }))} ref={ref} {...props}>
          {children}
        </Slot>
      )
    }

    return (
      <button
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        disabled={disabled || isLoading}
        {...props}
      >
        {isLoading && <Loader2 className="h-4 w-4 animate-spin" />}
        {children}
      </button>
    )
  }
)
Button.displayName = "Button"

export { Button, buttonVariants }
