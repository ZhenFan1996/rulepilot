import { cva, type VariantProps } from 'class-variance-authority'

export const buttonVariants = cva(
  'inline-flex items-center justify-center gap-2 rounded-full font-semibold transition-colors duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo focus-visible:ring-offset-2 focus-visible:ring-offset-canvas disabled:pointer-events-none disabled:opacity-50',
  {
    variants: {
      variant: {
        primary: 'bg-copper text-white shadow-[0_10px_24px_-12px_rgba(170,91,49,0.8)] hover:bg-copper-dark',
        secondary: 'bg-ink-panel text-canvas shadow-[0_10px_24px_-12px_rgba(26,35,42,0.55)] hover:bg-ink-panel/90',
        outline: 'border border-ink/15 bg-paper/60 text-ink hover:border-copper/60 hover:bg-copper/10',
        ghost: 'text-ink/70 hover:bg-ink/5 hover:text-ink',
      },
      size: {
        sm: 'min-h-10 px-4 text-sm',
        md: 'min-h-12 px-5 text-sm',
        lg: 'min-h-14 px-7 text-base',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'md',
    },
  },
)

export type ButtonVariant = VariantProps<typeof buttonVariants>['variant']
export type ButtonSize = VariantProps<typeof buttonVariants>['size']
