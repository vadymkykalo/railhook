/** @type {import('tailwindcss').Config} */
export default {
  darkMode: ["class"],
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    // Square corners; `full` stays for status dots and avatars.
    borderRadius: {
      none: "0",
      sm: "0",
      DEFAULT: "0",
      md: "0",
      lg: "0",
      xl: "0",
      "2xl": "0",
      "3xl": "0",
      full: "9999px",
    },
    // Only what floats (popover, menu, dialog) keeps a shadow.
    boxShadow: {
      none: "none",
      sm: "none",
      DEFAULT: "none",
      md: "0 4px 16px rgb(0 0 0 / 0.06)",
      lg: "0 8px 24px rgb(0 0 0 / 0.08)",
      xl: "0 8px 24px rgb(0 0 0 / 0.08)",
      "2xl": "0 8px 24px rgb(0 0 0 / 0.08)",
      inner: "none",
      card: "none",
      "card-hover": "none",
      elevated: "0 4px 16px rgb(0 0 0 / 0.06)",
      "elevated-lg": "0 8px 24px rgb(0 0 0 / 0.08)",
    },
    extend: {
      colors: {
        border: "hsl(var(--border))",
        input: "hsl(var(--input))",
        ring: "hsl(var(--ring))",
        rail: "hsl(var(--rail))",
        highlight: "hsl(var(--highlight))",
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
      fontFamily: {
        // Both faces carry Cyrillic; the product ships in Ukrainian too.
        sans: ['Geist', 'system-ui', '-apple-system', '"Segoe UI"', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      fontSize: {
        'display': ['3.5rem', { lineHeight: '1.16', letterSpacing: '-0.03em', fontWeight: '400' }],
        'headline': ['2.375rem', { lineHeight: '1.16', letterSpacing: '-0.02em', fontWeight: '400' }],
        'title': ['1.25rem', { lineHeight: '1.3', letterSpacing: '-0.01em', fontWeight: '500' }],
        'body-lg': ['1.0625rem', { lineHeight: '1.6' }],
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
