import { useMemo } from 'react';
import { cn } from '../lib/utils';

interface SparklineProps {
  data: number[];
  className?: string;
  width?: number;
  height?: number;
  strokeColor?: string;
  fillColor?: string;
  strokeWidth?: number;
}

export default function Sparkline({
  data,
  className,
  width = 100,
  height = 28,
  strokeColor = 'currentColor',
  fillColor,
  strokeWidth = 1.5,
}: SparklineProps) {
  const path = useMemo(() => {
    if (!data || data.length < 2) return null;

    const max = Math.max(...data, 1);
    const min = Math.min(...data, 0);
    const range = max - min || 1;
    const padding = 2;
    const w = width - padding * 2;
    const h = height - padding * 2;

    const points = data.map((v, i) => ({
      x: padding + (i / (data.length - 1)) * w,
      y: padding + h - ((v - min) / range) * h,
    }));

    const line = points.map((p, i) => (i === 0 ? `M${p.x},${p.y}` : `L${p.x},${p.y}`)).join(' ');

    const fill = fillColor
      ? `${line} L${points[points.length - 1].x},${height} L${points[0].x},${height} Z`
      : undefined;

    return { line, fill };
  }, [data, width, height, fillColor]);

  if (!path) return null;

  return (
    <svg
      aria-hidden
      focusable="false"
      viewBox={`0 0 ${width} ${height}`}
      width={width}
      height={height}
      className={cn('shrink-0', className)}
      preserveAspectRatio="none"
    >
      {path.fill && (
        <path d={path.fill} fill={fillColor} opacity={0.15} />
      )}
      <path
        d={path.line}
        fill="none"
        stroke={strokeColor}
        strokeWidth={strokeWidth}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}
