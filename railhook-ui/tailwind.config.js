/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        rail: "hsl(var(--rail))",
        background: "hsl(var(--background))",
        foreground: "hsl(var(--foreground))",
        primary: {
          DEFAULT: "hsl(var(--primary))",
          hover: "hsl(var(--primary-hover))",
          foreground: "hsl(var(--primary-foreground))",
        },
        secondary: {
          DEFAULT: "hsl(var(--secondary))",
          foreground: "hsl(var(--secondary-foreground))",
        },
        muted: {
          DEFAULT: "hsl(var(--muted))",
          foreground: "hsl(var(--muted-foreground))",
        },
        accent: {
          DEFAULT: "hsl(var(--accent))",
          foreground: "hsl(var(--accent-foreground))",
        },
        popover: {
          DEFAULT: "hsl(var(--popover))",
          foreground: "hsl(var(--popover-foreground))",
        },
        card: {
          DEFAULT: "hsl(var(--card))",
          foreground: "hsl(var(--card-foreground))",
        },

        // Status — reserved for statuses. Never use these for chrome.
        ok: {
          DEFAULT: "hsl(var(--ok))",
          soft: "hsl(var(--ok-soft))",
        },
        retry: {
          DEFAULT: "hsl(var(--retry))",
          soft: "hsl(var(--retry-soft))",
        },
        halt: {
          DEFAULT: "hsl(var(--halt))",
          soft: "hsl(var(--halt-soft))",
        },
        idle: {
          DEFAULT: "hsl(var(--idle))",
          soft: "hsl(var(--idle-soft))",
        },

        // Legacy aliases kept while shadcn primitives migrate to the above.
        destructive: {
          DEFAULT: "hsl(var(--destructive))",
          foreground: "hsl(var(--destructive-foreground))",
        },
        success: {
          DEFAULT: "hsl(var(--success))",
          foreground: "hsl(var(--success-foreground))",
        },
        warning: {
          DEFAULT: "hsl(var(--warning))",
          foreground: "hsl(var(--warning-foreground))",
        },
      },
      borderRadius: {
        // Controls are 10px (`lg`, --radius); cards are 16px (`xl`).
        xl: "calc(var(--radius) + 6px)",
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      fontFamily: {
        // Three faces, and all three carry full Cyrillic — the product ships in
        // English and Ukrainian, so a face without it would silently fall back
        // on every Ukrainian string. Onest is the body and the whole admin.
        // Manrope is display only: landing H1/H2 and docs page titles, never
        // the admin. JetBrains Mono is the machine voice — code, event types,
        // ids, URLs, eyebrows.
        display: ['Manrope', 'Onest', 'system-ui', 'sans-serif'],
        sans: ['Onest', 'system-ui', '-apple-system', '"Segoe UI"', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      fontSize: {
        'display': ['3.75rem', { lineHeight: '1.04', letterSpacing: '-0.035em', fontWeight: '800' }],
        'headline': ['2.375rem', { lineHeight: '1.12', letterSpacing: '-0.025em', fontWeight: '700' }],
        'title': ['1.3125rem', { lineHeight: '1.28', letterSpacing: '-0.015em', fontWeight: '600' }],
        'body-lg': ['1.0625rem', { lineHeight: '1.65' }],
      },
      boxShadow: {
        // Quiet. A card is drawn by its hairline; the shadow only lifts what floats.
        'card': '0 1px 2px 0 rgb(11 14 26 / 0.05)',
        'card-hover': '0 1px 2px rgb(11 14 26 / 0.05), 0 10px 30px rgb(11 14 26 / 0.07)',
        'elevated': '0 1px 2px rgb(11 14 26 / 0.05), 0 10px 30px rgb(11 14 26 / 0.07)',
        'elevated-lg': '0 2px 4px rgb(11 14 26 / 0.06), 0 16px 40px rgb(11 14 26 / 0.10)',
      },
      spacing: {
        '18': '4.5rem',
        '88': '22rem',
      },
      transitionDuration: {
        '250': '250ms',
      },
      animation: {
        'fade-in': 'fadeIn 0.3s ease-out forwards',
        'fade-in-up': 'fadeInUp 0.4s ease-out forwards',
        'slide-in-right': 'slideInRight 0.25s ease-out forwards',
        'scale-in': 'scaleIn 0.25s ease-out forwards',
      },
    },
  },
  plugins: [require('tailwindcss-animate')],
}
