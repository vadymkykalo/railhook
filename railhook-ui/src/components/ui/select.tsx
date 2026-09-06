import * as React from "react"
import * as SelectPrimitive from "@radix-ui/react-select"
import { ChevronDown, Check } from "lucide-react"
import { cn } from "../../lib/utils"

/* ── Option extraction helper ── */
const EMPTY_SENTINEL = '__EMPTY__'

interface OptionItem {
  value: string
  label: string
  disabled?: boolean
}

function extractOptions(children: React.ReactNode): OptionItem[] {
  const options: OptionItem[] = []
  React.Children.forEach(children, (child) => {
    if (!React.isValidElement(child)) return
    if (child.type === 'option') {
      const props = child.props as { value?: string; disabled?: boolean; children?: React.ReactNode }
      const raw = String(props.value ?? '')
      options.push({
        value: raw === '' ? EMPTY_SENTINEL : raw,
        label: String(props.children ?? props.value ?? ''),
        disabled: props.disabled,
      })
    }
  })
  return options
}

/* ── Drop-in Select (same API as native <select>) ── */
export interface SelectProps
  extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, 'onChange' | 'value'> {
  value?: string
  onChange?: (e: { target: { value: string } }) => void
}

const Select = React.forwardRef<HTMLButtonElement, SelectProps>(
  ({ className, children, value, onChange, disabled, id, ...rest }, ref) => {
    const options = extractOptions(children)

    const handleValueChange = (newValue: string) => {
      onChange?.({ target: { value: newValue === EMPTY_SENTINEL ? '' : newValue } })
    }

    const internalValue = value === '' ? EMPTY_SENTINEL : value
    const selectedLabel = options.find((o) => o.value === internalValue)?.label

    return (
      <SelectPrimitive.Root value={internalValue} onValueChange={handleValueChange} disabled={disabled}>
        <SelectPrimitive.Trigger
          ref={ref}
          id={id}
          aria-label={rest['aria-label']}
          aria-labelledby={rest['aria-labelledby']}
          /* The trigger is a <button>, so the global form-control rule in
             index.css cannot reach it — it has to spell out the field shape
             itself, and it spells out the same one: h-9 to sit level with
             Input and Button, bg-card so it is not paper-grey on white. Focus
             is left to the global `:focus-visible` outline; the old
             `focus:ring-ring/20 focus:border-ring` was a third focus look. */
          className={cn(
            "flex h-9 w-full items-center justify-between rounded-md border border-input bg-card px-3 py-2 text-sm placeholder:text-muted-foreground disabled:cursor-not-allowed disabled:opacity-50 [&>span]:truncate",
            className
          )}
        >
          <SelectPrimitive.Value placeholder={selectedLabel}>
            {selectedLabel}
          </SelectPrimitive.Value>
          <SelectPrimitive.Icon asChild>
            <ChevronDown className="h-4 w-4 opacity-50 flex-shrink-0" />
          </SelectPrimitive.Icon>
        </SelectPrimitive.Trigger>

        <SelectPrimitive.Portal>
          <SelectPrimitive.Content
            className="relative z-50 max-h-[240px] min-w-[8rem] overflow-hidden rounded-md border bg-popover text-popover-foreground shadow-md"
            position="popper"
            sideOffset={4}
          >
            <SelectPrimitive.Viewport className="p-1">
              {options.map((option) => (
                <SelectPrimitive.Item
                  key={option.value}
                  value={option.value}
                  disabled={option.disabled}
                  className={cn(
                    "relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none",
                    "focus:bg-accent focus:text-accent-foreground",
                    "data-[disabled]:pointer-events-none data-[disabled]:opacity-50",
                  )}
                >
                  <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
                    <SelectPrimitive.ItemIndicator>
                      <Check className="h-4 w-4" />
                    </SelectPrimitive.ItemIndicator>
                  </span>
                  <SelectPrimitive.ItemText>{option.label}</SelectPrimitive.ItemText>
                </SelectPrimitive.Item>
              ))}
            </SelectPrimitive.Viewport>
          </SelectPrimitive.Content>
        </SelectPrimitive.Portal>
      </SelectPrimitive.Root>
    )
  }
)
Select.displayName = "Select"

export { Select }
