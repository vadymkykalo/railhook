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
        xl: "calc(var(--radius) + 4px)",
        lg: "var(--radius)",
        md: "calc(var(--radius) - 2px)",
        sm: "calc(var(--radius) - 4px)",
      },
      fontFamily: {
        // One family, two voices. Geologica is a technical grotesk with full
        // Cyrillic — which decides it, because the product ships in English and
        // Ukrainian and the previous display face (Bricolage Grotesque) has no
        // Cyrillic at all, so every Ukrainian headline would have silently
        // fallen back. `display` and `sans` are the same family; the landing's
        // voice comes from weight and tracking, not from a second typeface.
        display: ['Geologica', 'system-ui', 'sans-serif'],
        sans: ['Geologica', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      fontSize: {
        'display': ['3.75rem', { lineHeight: '1.04', letterSpacing: '-0.04em', fontWeight: '600' }],
        'headline': ['2.375rem', { lineHeight: '1.1', letterSpacing: '-0.032em', fontWeight: '600' }],
        'title': ['1.3125rem', { lineHeight: '1.28', letterSpacing: '-0.018em', fontWeight: '600' }],
        'body-lg': ['1.0625rem', { lineHeight: '1.65' }],
      },
      boxShadow: {
        // No glows. Elevation is a shadow you would get from paper on paper.
        'card': '0 1px 2px 0 rgb(16 20 24 / 0.04)',
        'card-hover': '0 2px 8px -2px rgb(16 20 24 / 0.08), 0 1px 2px 0 rgb(16 20 24 / 0.04)',
        'elevated': '0 8px 24px -6px rgb(16 20 24 / 0.12), 0 2px 6px -2px rgb(16 20 24 / 0.06)',
        'elevated-lg': '0 16px 40px -10px rgb(16 20 24 / 0.16), 0 4px 10px -4px rgb(16 20 24 / 0.08)',
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
