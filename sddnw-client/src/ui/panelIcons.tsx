/**
 * Значки правой панели — редизайн главной сцены (07.10.2026, решение хозяина проекта).
 *
 * Большие картинки сегментов (корабль, куст, колба) убраны: они занимали половину панели и
 * ничего не сообщали, а главные числа под ними мельчали. Значок у заголовка держит узнаваемость
 * сегмента, не отнимая места. Рисуются линией `currentColor`, поэтому цвет задаёт роль текста,
 * а не сам значок.
 */
type IconProps = { className?: string };

const frame = (className: string | undefined, path: React.ReactNode) => (
  <svg viewBox="0 0 24 24" aria-hidden className={className ?? 'h-[1.1em] w-[1.1em] flex-none'}
       fill="none" stroke="currentColor" strokeWidth={1.6} strokeLinecap="round" strokeLinejoin="round">
    {path}
  </svg>
);

/** Галактический год — звезда-компас. */
export const YearIcon = ({ className }: IconProps) => frame(className, (
  <>
    <path d="M12 2.5 13.8 10.2 21.5 12 13.8 13.8 12 21.5 10.2 13.8 2.5 12 10.2 10.2Z" />
    <circle cx="12" cy="12" r="2" />
  </>
));

/** Казна — стопка монет. */
export const TreasuryIcon = ({ className }: IconProps) => frame(className, (
  <>
    <ellipse cx="12" cy="6" rx="7" ry="2.6" />
    <path d="M5 6v4c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6V6" />
    <path d="M5 10v4c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6v-4" />
    <path d="M5 14v4c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6v-4" />
  </>
));

/** Флот — стрела корабля носом вправо, как значок флота на карте. */
export const FleetIcon = ({ className }: IconProps) => frame(className, (
  <>
    <path d="M3 5 21 12 3 19 7 12Z" />
    <path d="M7 12h6" />
  </>
));

/** Еда — колос. */
export const FoodIcon = ({ className }: IconProps) => frame(className, (
  <>
    <path d="M12 21V8" />
    <path d="M12 9c-2.5 0-4-1.6-4-4 2.5 0 4 1.6 4 4Zm0 0c2.5 0 4-1.6 4-4-2.5 0-4 1.6-4 4Z" />
    <path d="M12 14c-2.5 0-4-1.6-4-4 2.5 0 4 1.6 4 4Zm0 0c2.5 0 4-1.6 4-4-2.5 0-4 1.6-4 4Z" />
  </>
));

/** Транспорт — грузовой отсек. */
export const TransportIcon = ({ className }: IconProps) => frame(className, (
  <>
    <path d="M3 8h13l5 4v4H3Z" />
    <path d="M3 12h18" />
    <circle cx="7.5" cy="18" r="1.5" />
    <circle cx="16.5" cy="18" r="1.5" />
  </>
));

/** Наука — колба. */
export const ScienceIcon = ({ className }: IconProps) => frame(className, (
  <>
    <path d="M9 3h6" />
    <path d="M10 3v6L4.5 19a1.5 1.5 0 0 0 1.3 2h12.4a1.5 1.5 0 0 0 1.3-2L14 9V3" />
    <path d="M7 15h10" />
  </>
));

/** Цели партии — флажок. */
export const GoalIcon = ({ className }: IconProps) => frame(className, (
  <>
    <path d="M5 21V4" />
    <path d="M5 4h12l-2.5 4L17 12H5" />
  </>
));

/** «Открыть» у заголовка сегмента. */
export const ChevronIcon = ({ className }: IconProps) => frame(className ?? 'h-[0.9em] w-[0.9em] flex-none', (
  <path d="m9 5 7 7-7 7" />
));

/* Значки нижней полосы — редизайн главной сцены: значок рядом со словом, как на наброске. */

/** Колонии — планета с воткнутым треугольным флагом: здесь поселились. */
export const ColoniesIcon = ({ className }: IconProps) => frame(className, (
  <>
    <circle cx="12" cy="15.5" r="6.5" />
    <path d="M7.5 17.5c1.5.8 3.2 1 4.8.7" />
    <path d="M12 9V2.5" />
    <path d="M12 2.5 17.5 4.5 12 6.5Z" fill="currentColor" />
  </>
));

/** Планеты — шар с меридианом. */
export const PlanetsIcon = ({ className }: IconProps) => frame(className, (
  <>
    <circle cx="12" cy="12" r="8" />
    <path d="M4 12h16" />
    <path d="M12 4c2.5 2.2 3.6 5 3.6 8s-1.1 5.8-3.6 8c-2.5-2.2-3.6-5-3.6-8s1.1-5.8 3.6-8Z" />
  </>
));

/** Меню «Игра» — шестерня. */
export const GameIcon = ({ className }: IconProps) => frame(className, (
  <>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2.8v2.6M12 18.6v2.6M2.8 12h2.6M18.6 12h2.6M5.5 5.5l1.8 1.8M16.7 16.7l1.8 1.8M5.5 18.5l1.8-1.8M16.7 7.3l1.8-1.8" />
  </>
));

/** Лидеры — фигура со звездой. */
export const LeadersIcon = ({ className }: IconProps) => frame(className, (
  <>
    <circle cx="12" cy="8" r="3.5" />
    <path d="M5 20c.8-3.6 3.6-5.5 7-5.5s6.2 1.9 7 5.5" />
  </>
));

/** Расы — две фигуры. */
export const RacesIcon = ({ className }: IconProps) => frame(className, (
  <>
    <circle cx="9" cy="8.5" r="3" />
    <circle cx="16.5" cy="9.5" r="2.5" />
    <path d="M3.5 20c.7-3.2 3-5 5.5-5s4.8 1.8 5.5 5" />
    <path d="M14.5 14.6c2.6-.2 4.8 1.4 5.5 4.4" />
  </>
));

/** Инфо — «i» в круге. */
export const InfoIcon = ({ className }: IconProps) => frame(className, (
  <>
    <circle cx="12" cy="12" r="8.5" />
    <path d="M12 11v5.5" />
    <path d="M12 7.6v.1" strokeWidth={2.4} />
  </>
));
