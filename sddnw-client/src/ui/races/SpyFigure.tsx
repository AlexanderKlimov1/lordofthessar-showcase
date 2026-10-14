/**
 * Фигурка агента разведки — п. 13 (09.10.2026, баг хозяина проекта: пульт «Расы» писал
 * «агентов 1», а самого агента не было видно нигде). Шляпа и плащ, как у жителя колонии —
 * фигурка, а не значок: агент — это человек на службе, и считать их глазом проще, чем
 * читать число. Цвет — роль состояния: дома, на защите или за границей.
 */
export function SpyFigure({ className = 'h-[22px] w-[16px]', color = 'currentColor' }: {
  className?: string;
  color?: string;
}) {
  return (
    <svg viewBox="0 0 16 22" className={className} aria-hidden="true">
      {/* Шляпа с полями. */}
      <rect x="3" y="4.2" width="10" height="1.4" rx="0.7" fill={color} />
      <path d="M5 4.4 Q5 1 8 1 Q11 1 11 4.4 Z" fill={color} />
      {/* Голова под шляпой. */}
      <circle cx="8" cy="7.2" r="2.1" fill={color} opacity="0.75" />
      {/* Плащ с поднятым воротником. */}
      <path d="M8 9.6 L3.2 11.2 L2.4 21 L13.6 21 L12.8 11.2 Z" fill={color} />
      <path d="M8 9.6 L6 13.5 L8 12.4 L10 13.5 Z" fill="#05070f" opacity="0.45" />
    </svg>
  );
}
