import { useEffect, useRef, useState } from 'react';
import { prefersReducedMotion } from './primitives';

/** `static` is the finished picture; `running` means the loop plays whenever it is on screen. */
export type Motion = 'static' | 'running';

/**
 * Starts, pauses and resumes a looping animation with the reader's attention: it runs only while
 * its element is on screen and the tab is visible, and never under prefers-reduced-motion.
 *
 * Until it first runs, and for good when motion is reduced, `motion` stays `static`, which is the
 * state every caller renders as its finished picture. A crawler, the prerender and a test all see
 * that picture, and so does a reader who asked for stillness.
 *
 * `onFrame` receives the time since the loop started, with pauses left out, so a tab left in the
 * background resumes where it stopped instead of skipping ahead.
 */
export function useAnimationGate<T extends Element>(onFrame: (elapsedMs: number) => void) {
  const ref = useRef<T | null>(null);
  const [motion, setMotion] = useState<Motion>('static');
  const frameRef = useRef(onFrame);
  frameRef.current = onFrame;

  useEffect(() => {
    const node = ref.current;
    if (
      !node
      || prefersReducedMotion()
      || typeof IntersectionObserver === 'undefined'
      || typeof requestAnimationFrame !== 'function'
    ) {
      return;
    }

    let onScreen = false;
    let frame = 0;
    let elapsed = 0;
    let last = 0;

    const tick = (now: number) => {
      // A long gap is a throttled or backgrounded tab; do not jump the scene forward by it.
      if (last) elapsed += Math.min(now - last, 100);
      last = now;
      frameRef.current(elapsed);
      frame = requestAnimationFrame(tick);
    };
    const start = () => {
      if (frame || !onScreen || document.hidden) return;
      last = 0;
      frame = requestAnimationFrame(tick);
      setMotion('running');
    };
    const stop = () => {
      if (!frame) return;
      cancelAnimationFrame(frame);
      frame = 0;
    };

    const observer = new IntersectionObserver(
      (entries) => {
        onScreen = entries.some((entry) => entry.isIntersecting);
        if (onScreen) start();
        else stop();
      },
      { rootMargin: '80px 0px' },
    );
    observer.observe(node);
    const onVisibility = () => (document.hidden ? stop() : start());
    document.addEventListener('visibilitychange', onVisibility);

    return () => {
      observer.disconnect();
      document.removeEventListener('visibilitychange', onVisibility);
      stop();
    };
  }, []);

  return { ref, motion };
}

/**
 * The position inside a looping scene, in milliseconds, re-rendered at a modest frame rate.
 * `null` means the finished picture (see {@link useAnimationGate}).
 */
export function useSceneClock<T extends Element>(loopMs: number, fps = 30) {
  const [time, setTime] = useState<number | null>(null);
  const lastPaint = useRef(-Infinity);
  const { ref, motion } = useAnimationGate<T>((elapsed) => {
    if (elapsed - lastPaint.current < 1000 / fps && elapsed >= lastPaint.current) return;
    lastPaint.current = elapsed;
    setTime(elapsed % loopMs);
  });
  return { ref, motion, time: motion === 'running' ? time ?? 0 : null };
}
