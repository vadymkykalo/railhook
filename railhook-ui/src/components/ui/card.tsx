import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "../../lib/utils"

/* Density is a card-level decision, not a per-slot one: a card whose header is
   p-6 and whose content is p-4 reads as a misprint. Card publishes its density
   through context so the slots inherit it, and each slot still accepts an
   explicit `density` for the rare hand-tuned case. `comfortable` is the
   default and is byte-identical to the previous hardcoded p-6, so none of the
   ~35 existing call sites move; `compact` is the density an operations tool
   actually wants, and is what the ~166 per-page `p-4`/`p-3` overrides were
   reaching for. */
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
  "rounded-xl border bg-card text-card-foreground shadow-card transition-shadow duration-200",
  {
    variants: {
      /* The lift used to be unconditional, so a static summary card rose under
         the cursor as if it were a link. Most cards here are read-only, so the
         affordance is opt-in and only cards that go somewhere ask for it. */
      interactive: {
        true: "hover:shadow-card-hover",
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
    /* `text-2xl` predates the type scale; `text-title` is the scale's step for
       a panel heading and carries its own weight and tracking. */
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
