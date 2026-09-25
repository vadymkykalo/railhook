import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "../../lib/utils"

/* Density is per card (via context): a p-6 header over p-4 content reads as a misprint. */
export type CardDensity = "comfortable" | "compact"

const CardDensityContext = React.createContext<CardDensity>("comfortable")

const cardPadding = cva("", {
  variants: {
    density: {
      comfortable: "p-6",
      compact: "p-4",
    },
  },
  defaultVariants: {
    density: "comfortable",
  },
})

const cardVariants = cva(
  "border bg-card text-card-foreground transition-colors duration-200",
  {
    variants: {
      interactive: {
        true: "hover:border-foreground",
        false: "",
      },
    },
    defaultVariants: {
      interactive: false,
    },
  }
)

export interface CardProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof cardVariants> {
  density?: CardDensity
}

export interface CardSlotProps extends React.HTMLAttributes<HTMLDivElement> {
  density?: CardDensity
}

const Card = React.forwardRef<HTMLDivElement, CardProps>(
  ({ className, interactive, density = "comfortable", ...props }, ref) => (
    <CardDensityContext.Provider value={density}>
      <div
        ref={ref}
        className={cn(cardVariants({ interactive }), className)}
        {...props}
      />
    </CardDensityContext.Provider>
  )
)
Card.displayName = "Card"

const CardHeader = React.forwardRef<HTMLDivElement, CardSlotProps>(
  ({ className, density, ...props }, ref) => {
    const inherited = React.useContext(CardDensityContext)
    return (
      <div
        ref={ref}
        className={cn(
          "flex flex-col space-y-1.5",
          cardPadding({ density: density ?? inherited }),
          className
        )}
        {...props}
      />
    )
  }
)
CardHeader.displayName = "CardHeader"

const CardTitle = React.forwardRef<
  HTMLParagraphElement,
  React.HTMLAttributes<HTMLHeadingElement>
>(({ className, ...props }, ref) => (
  <h3
    ref={ref}
    className={cn("text-title", className)}
    {...props}
  />
))
CardTitle.displayName = "CardTitle"

const CardDescription = React.forwardRef<
  HTMLParagraphElement,
  React.HTMLAttributes<HTMLParagraphElement>
>(({ className, ...props }, ref) => (
  <p
    ref={ref}
    className={cn("text-sm text-muted-foreground", className)}
    {...props}
  />
))
CardDescription.displayName = "CardDescription"

const CardContent = React.forwardRef<HTMLDivElement, CardSlotProps>(
  ({ className, density, ...props }, ref) => {
    const inherited = React.useContext(CardDensityContext)
    return (
      <div
        ref={ref}
        className={cn(cardPadding({ density: density ?? inherited }), "pt-0", className)}
        {...props}
      />
    )
  }
)
CardContent.displayName = "CardContent"

const CardFooter = React.forwardRef<HTMLDivElement, CardSlotProps>(
  ({ className, density, ...props }, ref) => {
    const inherited = React.useContext(CardDensityContext)
    return (
      <div
        ref={ref}
        className={cn("flex items-center", cardPadding({ density: density ?? inherited }), "pt-0", className)}
        {...props}
      />
    )
  }
)
CardFooter.displayName = "CardFooter"

export { Card, CardHeader, CardFooter, CardTitle, CardDescription, CardContent, cardVariants }
