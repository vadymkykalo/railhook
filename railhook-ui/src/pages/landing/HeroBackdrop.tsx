import { useEffect, useRef } from 'react';
import { prefersReducedMotion } from './primitives';
import { useAnimationGate } from './useSceneClock';

/**
 * The ground behind the hero: faint rails across the page, carrying the same ladder ticks as the
 * section dividers, with deliveries travelling from one station to the next. Most arrive and ring
 * green; now and then one waits amber at the station — a retry — before it rings green too.
 *
 * Deliberately quiet and deliberately different from the maps further down: straight rails, no
 * hub, no names, nothing to read. A CSS mask (`.hero-backdrop`) clears the middle, where the
 * headline and the buttons are, so the motion only ever lives at the edges.
 *
 * Canvas rather than SVG because the number of moving things is open-ended; it is capped at 2×
 * device pixels, runs only while on screen and on a visible tab, and under
 * prefers-reduced-motion draws one still composition instead.
 */

const LADDER = [0, 1, 5, 15, 60, 360, 1440];
const ladderAt = (minutes: number) => (minutes <= 0 ? 0 : Math.log1p(minutes) / Math.log1p(1440));

const RAIL_ROWS = [0.1, 0.23, 0.36, 0.5, 0.64, 0.78, 0.91];
const HOLD_MS = 1500;
const RING_MS = 1000;

interface Delivery {
  rail: number;
  from: number;
  to: number;
  startedAt: number;
  duration: number;
  retries: boolean;
  arrivedAt?: number;
}

interface Ring {
  x: number;
  y: number;
  at: number;
}

interface Palette {
  rail: string;
  tick: string;
  station: string;
  packet: string;
  trail: string;
  retry: string;
  ok: string;
}

/** A small deterministic generator, so the still composition is the same on every visit. */
function random(seed: number) {
  let s = seed >>> 0;
  return () => {
    s += 0x6d2b79f5;
    let t = s;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function readPalette(): Palette {
  const css = getComputedStyle(document.documentElement);
  const hsl = (name: string, alpha: number) => `hsl(${css.getPropertyValue(name).trim()} / ${alpha})`;
  return {
    rail: hsl('--input', 0.75),
    tick: hsl('--input', 0.9),
    station: hsl('--input', 1),
    packet: hsl('--primary', 0.85),
    trail: hsl('--primary', 0),
    retry: hsl('--retry', 0.9),
    ok: hsl('--ok', 0.85),
  };
}

export default function HeroBackdrop() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const world = useRef({
    width: 0,
    height: 0,
    segment: 400,
    offsets: RAIL_ROWS.map(() => 0),
    deliveries: [] as Delivery[],
    rings: [] as Ring[],
    nextSpawn: RAIL_ROWS.map(() => 0),
    rand: random(7),
    palette: null as Palette | null,
    context: null as CanvasRenderingContext2D | null,
  });

  const stationX = (rail: number, index: number) => world.current.offsets[rail] + index * world.current.segment;
  const railY = (rail: number) => RAIL_ROWS[rail] * world.current.height;

  function drawGround(ctx: CanvasRenderingContext2D, p: Palette) {
    const w = world.current;
    ctx.clearRect(0, 0, w.width, w.height);
    ctx.lineWidth = 1;
    RAIL_ROWS.forEach((_, rail) => {
      const y = Math.round(railY(rail)) + 0.5;
      ctx.strokeStyle = p.rail;
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w.width, y);
      ctx.stroke();

      ctx.strokeStyle = p.tick;
      for (let i = -1; stationX(rail, i) < w.width; i++) {
        const left = stationX(rail, i);
        for (const minutes of LADDER.slice(1, -1)) {
          const x = Math.round(left + ladderAt(minutes) * w.segment) + 0.5;
          ctx.beginPath();
          ctx.moveTo(x, y - 3);
          ctx.lineTo(x, y + 3);
          ctx.stroke();
        }
        ctx.fillStyle = p.station;
        ctx.beginPath();
        ctx.arc(left, y, 2.5, 0, Math.PI * 2);
        ctx.fill();
      }
    });
  }

  function drawPacket(ctx: CanvasRenderingContext2D, p: Palette, x: number, y: number, color: string, trail = 42) {
    const gradient = ctx.createLinearGradient(x - trail, y, x, y);
    gradient.addColorStop(0, p.trail);
    gradient.addColorStop(1, color);
    ctx.strokeStyle = gradient;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(x - trail, y);
    ctx.lineTo(x, y);
    ctx.stroke();
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(x, y, 2.8, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawRing(ctx: CanvasRenderingContext2D, x: number, y: number, color: string, phase: number) {
    ctx.strokeStyle = color;
    ctx.globalAlpha = Math.max(0, 1 - phase);
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.arc(x, y, 3 + phase * 13, 0, Math.PI * 2);
    ctx.stroke();
    ctx.globalAlpha = 1;
  }

  function drawStill() {
    const w = world.current;
    const ctx = w.context;
    if (!ctx || !w.palette) return;
    const p = w.palette;
    drawGround(ctx, p);
    const rand = random(11);
    RAIL_ROWS.forEach((_, rail) => {
      const y = railY(rail);
      const index = Math.floor(rand() * 3);
      const x = stationX(rail, index) + (0.35 + rand() * 0.5) * w.segment;
      if (rail % 3 === 1) {
        const station = stationX(rail, index + 1);
        ctx.fillStyle = p.retry;
        ctx.beginPath();
        ctx.arc(station, y, 3.2, 0, Math.PI * 2);
        ctx.fill();
        drawRing(ctx, station, y, p.retry, 0.35);
      } else {
        drawPacket(ctx, p, x, y, p.packet);
        if (rail % 2 === 0) drawRing(ctx, stationX(rail, index), y, p.ok, 0.4);
      }
    });
  }

  function spawn(rail: number, now: number) {
    const w = world.current;
    // Start at a station somewhere on screen, or just left of it, and head for the next one.
    const first = Math.floor(-w.offsets[rail] / w.segment) - 1;
    const last = Math.floor((w.width - w.offsets[rail]) / w.segment);
    const index = first + Math.floor(w.rand() * Math.max(1, last - first));
    w.deliveries.push({
      rail,
      from: stationX(rail, index),
      to: stationX(rail, index + 1),
      startedAt: now,
      duration: 3200 + w.rand() * 2600,
      retries: w.rand() < 0.22,
    });
    w.nextSpawn[rail] = now + 1600 + w.rand() * 3400;
  }

  function frame(now: number) {
    const w = world.current;
    const ctx = w.context;
    if (!ctx || !w.palette) return;
    const p = w.palette;
    drawGround(ctx, p);

    RAIL_ROWS.forEach((_, rail) => {
      const busy = w.deliveries.filter((d) => d.rail === rail).length;
      if (now >= w.nextSpawn[rail] && busy < 2) spawn(rail, now);
    });

    w.deliveries = w.deliveries.filter((d) => {
      const y = railY(d.rail);
      const t = Math.min(1, (now - d.startedAt) / d.duration);
      if (t < 1) {
        const eased = t < 0.5 ? 2 * t * t : 1 - (-2 * t + 2) ** 2 / 2;
        drawPacket(ctx, p, d.from + (d.to - d.from) * eased, y, p.packet);
        return true;
      }
      d.arrivedAt ??= now;
      if (d.retries && now - d.arrivedAt < HOLD_MS) {
        const pulse = ((now - d.arrivedAt) % 750) / 750;
        ctx.fillStyle = p.retry;
        ctx.beginPath();
        ctx.arc(d.to, y, 3.2, 0, Math.PI * 2);
        ctx.fill();
        drawRing(ctx, d.to, y, p.retry, pulse);
        return true;
      }
      w.rings.push({ x: d.to, y, at: now });
      return false;
    });

    w.rings = w.rings.filter((ring) => {
      const phase = (now - ring.at) / RING_MS;
      if (phase >= 1) return false;
      drawRing(ctx, ring.x, ring.y, p.ok, phase);
      return true;
    });
  }

  const { ref: gateRef, motion } = useAnimationGate<HTMLCanvasElement>(frame);
  // The effect below is set up once; it reaches the current drawing through this ref.
  const drawStillRef = useRef(drawStill);
  drawStillRef.current = drawStill;

  useEffect(() => {
    const drawStillNow = () => drawStillRef.current();
    const canvas = canvasRef.current;
    const host = canvas?.parentElement;
    if (!canvas || !host) return;

    const w = world.current;
    const setup = () => {
      const context = w.context ?? canvas.getContext('2d');
      if (!context) return false;
      w.context = context;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      w.width = host.clientWidth;
      w.height = host.clientHeight;
      w.segment = Math.min(520, Math.max(260, w.width / 3));
      const rand = random(3);
      w.offsets = RAIL_ROWS.map(() => -rand() * w.segment);
      canvas.width = Math.round(w.width * dpr);
      canvas.height = Math.round(w.height * dpr);
      context.setTransform(dpr, 0, 0, dpr, 0, 0);
      w.palette = readPalette();
      w.deliveries = [];
      w.rings = [];
      if (prefersReducedMotion()) drawStillNow();
      return true;
    };

    // Nothing is drawn until the hero is on screen: jsdom and the prerender never ask for a canvas.
    let ready = false;
    const observer = typeof IntersectionObserver === 'undefined'
      ? null
      : new IntersectionObserver((entries) => {
          if (!ready && entries.some((entry) => entry.isIntersecting)) ready = setup();
        });
    observer?.observe(canvas);

    const resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => ready && setup());
    resize?.observe(host);

    // The theme toggle flips a class on <html>; the colours are read again with it.
    const theme = new MutationObserver(() => {
      if (!ready) return;
      w.palette = readPalette();
      if (prefersReducedMotion()) drawStillNow();
    });
    theme.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });

    return () => {
      observer?.disconnect();
      resize?.disconnect();
      theme.disconnect();
    };
  }, []);

  return (
    <canvas
      ref={(node) => {
        canvasRef.current = node;
        gateRef.current = node;
      }}
      aria-hidden="true"
      data-motion={motion}
      className="hero-backdrop pointer-events-none absolute inset-0 -z-10 h-full w-full"
    />
  );
}
