import type { ReactNode } from 'react';
import { figureClass, figureIconClass, toneGlyph, type Accent, type Tone } from './accent';

/**
 * Число на сцене — со ступенью выделения (`accent.ts`). Всякое отдельно стоящее число игры
 * рисуется этим компонентом, а не своим `<span className="text-…">`: иначе ступень снова
 * подбиралась бы по месту, и одинаковые по смыслу числа разошлись бы видом.
 *
 * `icon` — значок при числе (монеты, колос): получает размер и цвет той же ступени через
 * функцию, потому что значки игры — компоненты со своим `className`. `title` — подсказка,
 * если число без неё непонятно. Числа ВНУТРИ фразы перевода («8 из 16 жителей») сюда не
 * идут: фраза — одна строка словаря, и рвать её ради числа значит рвать перевод.
 */
/**
 * Срок до конца стройки — вдвое крупнее ступени своей строки (12.10.2026, решение хозяина
 * проекта: «срок — важная цифра»). По нему решают, что строить и что выкупать, а одной ступенью
 * со всеми числами строки он терялся. Цвет и начертание — ступени, размер — вдвое.
 */
const DOUBLE: Record<Accent, string> = {
  note: 'text-28',
  value: 'text-36',
  lead: 'text-44',
  key: 'text-88',
};

export function TurnsFigure({ accent, children }: { accent: Accent; children: ReactNode }) {
  return (
    <span className={figureClass(accent).replace(/text-\d+/, DOUBLE[accent]) + ' leading-none'}>
      {children}
    </span>
  );
}

export function Figure({
  accent,
  tone,
  icon,
  title,
  className = '',
  children,
}: {
  accent: Accent;
  tone?: Tone;
  icon?: (className: string) => ReactNode;
  title?: string;
  className?: string;
  children: ReactNode;
}) {
  const glyph = toneGlyph(tone, children);
  const number = <span className={figureClass(accent, tone)}>{glyph ? `${glyph} ` : null}{children}</span>;
  if (!icon && !title && !className) {
    return number;
  }
  return (
    <span className={'inline-flex items-center gap-1 ' + className} title={title}>
      {number}
      {icon ? icon(figureIconClass(accent, tone)) : null}
    </span>
  );
}
