import { useState } from 'react';
import { useT } from '../i18n';
import { useGameStore } from '../state/gameStore';
import { useHudShown } from '../state/hudPresence';
import { useModalOpen } from './useModalEscape';

/**
 * Красное око — партия под наблюдением администратора (field-backlog, пункт 197, решение
 * хозяина проекта, 05.10.2026).
 *
 * «Показать галактику» у администратора осталось и в партии с людьми: им живут отладка и
 * проверки. Но один, видящий карты остальных, — тяжелейшее нарушение честного стола, и
 * скрывать его от сидящих за столом нельзя. Сервер помечает такую партию
 * (`GameSummaryDto.adminWatched`), а здесь пометка видна КАЖДОМУ за этим столом — маленьким
 * красным оком в углу, рядом со значком звука, на любой сцене партии. Подсказка всплывает
 * по наведению сразу, без задержки системной: её ищут глазами, а не ждут.
 *
 * Рисуется поверх всего (`main.tsx`), как и значок звука: сцен в партии десятки, и око,
 * вписанное в каждую, однажды забыли бы в новой.
 */
export function AdminWatchEye({ inline = false }: {
  /** В нижней полосе карты, рядом со значком звука (07.10.2026); без признака — в углу. */
  inline?: boolean;
} = {}) {
  const { t } = useT();
  const watched = useGameStore((state) => state.game?.adminWatched === true);
  const [hover, setHover] = useState(false);
  const hud = useHudShown();
  const modal = useModalOpen();
  if (!watched || (!inline && hud && !modal)) {
    return null;
  }
  const label = t('adminWatch.hint');
  return (
    <div
      role="img"
      aria-label={label}
      tabIndex={0}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      onFocus={() => setHover(true)}
      onBlur={() => setHover(false)}
      className={`${inline ? 'relative h-5 w-5' : 'fixed left-10 top-1.5 z-[80] h-7 w-7 rounded border border-danger/60 bg-space-950/70'} flex items-center justify-center text-danger`}
    >
      <svg viewBox="0 0 24 24" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8"
           strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z" />
        <circle cx="12" cy="12" r="3.2" fill="currentColor" />
      </svg>
      {hover ? (
        <span className={`pointer-events-none absolute left-0 ${inline ? 'bottom-full mb-1' : 'top-full mt-1'} whitespace-nowrap border border-danger/60 bg-space-950 px-2 py-1 text-xs text-ink`}>
          {label}
        </span>
      ) : null}
    </div>
  );
}
