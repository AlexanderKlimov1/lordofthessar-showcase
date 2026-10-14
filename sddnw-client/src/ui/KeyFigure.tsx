import { figureClass, toneGlyph, type Tone } from './accent';

/**
 * Главное число экрана — backlog-promo, пункт 13: ступень `key` (`accent.ts`) с подписью.
 *
 * У экрана ОДНО главное число (решение хозяина проекта: у колонии — срок стройки, у списка
 * колоний — баланс еды, у флота — сила выбранного флота, у «Инфо» — своё место среди
 * знакомых). Как оно выглядит, решает ступень, а не этот компонент: сам он добавляет только
 * подпись. Второго такого числа на экране быть не должно: два главных — это снова ни одного.
 */
export function KeyFigure({
  value,
  label,
  tone,
  className = '',
  title,
  double = false,
}: {
  value: string;
  label: string;
  tone?: Tone;
  className?: string;
  title?: string;
  /** Срок до конца стройки — вдвое крупнее (12.10.2026, `TurnsFigure`). */
  double?: boolean;
}) {
  return (
    // Подпись НАД числом, а не после него: «4 хода», «1 ход», «5 ходов» потребовали бы
    // склонения, а «до готовности, ходов: 4» читается на обоих языках без него.
    <span className={'inline-flex flex-col items-center gap-1 ' + className} title={title}>
      <span className="text-16 uppercase leading-tight tracking-[0.15em] text-ink-dim">{label}</span>
      <span className={double ? figureClass('key', tone).replace(/text-\d+/, 'text-88') : figureClass('key', tone)}>{toneGlyph(tone, value) ? `${toneGlyph(tone, value)} ` : null}{value}</span>
    </span>
  );
}
