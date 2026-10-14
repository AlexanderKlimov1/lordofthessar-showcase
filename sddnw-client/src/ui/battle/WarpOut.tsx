import { useEffect, useId, type CSSProperties } from 'react';
import { CELL, fieldShipSize } from './battleVisuals';
import { ShipSprite } from '../ship/shipArt';

/**
 * Уход в гиперпространство — п. 8 (06.10.2026; разметка ухода — 09.10.2026, решение хозяина
 * проекта). Прежде корабль срывался к краю поля за секунду, и уход читался как исчезновение.
 * Теперь у него три видимых шага:
 *
 *  1. корабль РАЗВОРАЧИВАЕТСЯ носом к своему краю (`turn`: если приказ дан в этом же ответе —
 *     до того корабль смотрел на врага; отдан раньше — он уже стоит развёрнутым);
 *  2. перед носом РАСКРЫВАЕТСЯ воронка прыжка — вертикальный овал с вихрем по кромке;
 *  3. корабль ПРОВАЛИВАЕТСЯ в неё — подходит, сжимается и гаснет, а воронка схлопывается
 *     вспышкой.
 *
 * Анимация на CSS (`sddnw-warp-*` в `index.css`), а не на SMIL: CSS начинается в миг вставки
 * элемента, а у SMIL отсчёт идёт от загрузки документа. Повороты и сжатие — вокруг своей
 * середины (`transform-box: fill-box`). По концу корабль убирается.
 */

/** Сколько длится уход, миллисекунды. */
const LIFETIME_MS = 1900;

/** Свойства анимации вокруг своей середины: так поворачивается и сжимается сама фигура. */
const around = (animation: string, extra?: CSSProperties): CSSProperties => ({
  transformBox: 'fill-box',
  transformOrigin: 'center',
  animation: `${animation} ${LIFETIME_MS}ms ease-in-out both`,
  ...extra,
});

export function WarpOut({
  x,
  y,
  hullCode,
  hullSize,
  color,
  toLeft,
  turn,
  onDone,
}: {
  /** Клетка, с которой корабль уходит. */
  x: number;
  y: number;
  hullCode?: string;
  hullSize: number;
  color: string;
  /** К какому краю уходит: свой край — за спиной у корабля, смотревшего на врага. */
  toLeft: boolean;
  /** Развернуться ли сперва: приказ отдан в этом же ходу, и корабль ещё смотрит на врага. */
  turn: boolean;
  onDone: () => void;
}) {
  const gradient = `warp-${useId().replace(/:/g, '')}`;
  useEffect(() => {
    const timer = window.setTimeout(onDone, LIFETIME_MS);
    return () => window.clearTimeout(timer);
  }, [onDone]);

  const { length, width } = fieldShipSize(hullSize);
  const cx = x * CELL + CELL / 2;
  const cy = y * CELL + CELL / 2;
  const sign = toLeft ? -1 : 1;
  // Воронка — перед носом, на клетку с лишним: корабль должен до неё дойти.
  const dx = sign * (length / 2 + CELL * 1.2);
  const ry = Math.max(width, CELL * 0.6) * 1.1;
  const rx = ry * 0.38;
  const px = cx + dx;

  return (
    <g pointerEvents="none">
      <defs>
        <radialGradient id={gradient}>
          <stop offset="0%" stopColor="#020617" />
          <stop offset="55%" stopColor="#0c1a3a" />
          <stop offset="85%" stopColor="#38bdf8" stopOpacity="0.85" />
          <stop offset="100%" stopColor="#e0f2fe" stopOpacity="0.9" />
        </radialGradient>
      </defs>

      {/* Воронка прыжка: раскрывается перед носом и схлопывается, когда корабль в ней. */}
      <g style={around('sddnw-warp-portal')}>
        <ellipse cx={px} cy={cy} rx={rx * 1.6} ry={ry * 1.25} fill="#7dd3fc" opacity={0.18} />
        <ellipse cx={px} cy={cy} rx={rx} ry={ry} fill={`url(#${gradient})`} />
        <ellipse cx={px} cy={cy} rx={rx * 0.8} ry={ry * 0.85} fill="none" stroke="#bae6fd"
                 strokeWidth={1.2} strokeDasharray="6 5" opacity={0.8}
                 style={{ animation: 'sddnw-warp-swirl 600ms linear infinite' }} />
      </g>

      {/* Корабль: подходит к воронке, сжимается и гаснет в ней. */}
      <g style={around('sddnw-warp-fall', { ['--dx' as string]: `${dx}px` })}>
        <g style={turn ? around('sddnw-warp-turn') : undefined}>
          <ShipSprite hullCode={hullCode} hullSize={hullSize} color={color}
                      cx={cx} cy={cy} size={length} height={width} left={toLeft} />
        </g>
      </g>

      {/* Вспышка схлопнувшейся воронки. */}
      <circle cx={px} cy={cy} r={ry * 0.9} fill="#e0f2fe" style={around('sddnw-warp-flash')} />
    </g>
  );
}
